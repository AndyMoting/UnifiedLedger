package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.data.db.LedgerDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
                JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver -> LedgerDatabase.Schema.migrate(driver, 6, 11) }
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
    fun freshSchemaCreatesEveryLedgerDataTableAtVersionSeventeen() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            SqlDelightConfirmedManualExpenseCommitPort(database, driver)

            assertEquals(17, LedgerDatabase.Schema.version)
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
    fun populatedVersionNinePreservesFormalRowsAndAddsRg07OwnersAtVersionTen() {
        val path = Files.createTempFile("ledger-data-v9-v10-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            Files.copy(
                migrationRepositoryFile("ledger-data/src/commonMain/sqldelight/databases/9.db"),
                path,
                StandardCopyOption.REPLACE_EXISTING,
            )
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE name LIKE 'rg07%'"))
                driver.execute(
                    null,
                    "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('transaction-v9', 'ledger-v9', 'EXPENSE')",
                    0,
                )
                database.ledgerQueries.insertPostingSet("posting-set-v9", "ledger-v9")
                database.ledgerQueries.insertTransactionVersion("version-v9", "transaction-v9", "ledger-v9", 1, "posting-set-v9", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z", "")
                database.ledgerQueries.insertTransactionCurrentVersion("transaction-v9", "ledger-v9", "version-v9")
                database.ledgerQueries.insertPosting("posting-v9-expense", "posting-set-v9", "ledger-v9", 0, "expense-v9", 1000, "CNY", 2)
                database.ledgerQueries.insertPosting("posting-v9-asset", "posting-set-v9", "ledger-v9", 1, "asset-v9", -1000, "CNY", 2)
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, oldVersion = 9, newVersion = 10)
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countVersions().executeAsOne())
                assertEquals(2L, database.ledgerQueries.countPostings().executeAsOne())
                assertEquals(
                    0L,
                    queryCount(
                        driver,
                        "SELECT count(*) FROM pragma_table_info('rg06_confirmation') WHERE name = 'confirmed_at'",
                    ),
                )
                database.ledgerQueries.insertTransaction("refund-v10", "ledger-v9", "REFUND_RECEIPT")
                val rg07Table = driver.executeQuery(null, "SELECT name FROM sqlite_master WHERE type='table' AND name='rg07_operation'", { cursor ->
                    cursor.next()
                    app.cash.sqldelight.db.QueryResult.Value(cursor.getString(0))
                }, 0).value
                assertEquals("rg07_operation", rg07Table)
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun versionTenToElevenAddsConfirmedAtAndPreservesRowsAcrossReopen() {
        val path = Files.createTempFile("ledger-data-v10-v11-rg06-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            Files.copy(
                migrationRepositoryFile("ledger-data/src/commonMain/sqldelight/databases/9.db"),
                path,
                StandardCopyOption.REPLACE_EXISTING,
            )
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, oldVersion = 9, newVersion = 10)
                driver.execute(null, "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('transaction-v10', 'ledger-v10', 'EXPENSE')", 0)
                driver.execute(null, "INSERT INTO posting_set(posting_set_id, ledger_id) VALUES ('posting-set-v10', 'ledger-v10')", 0)
                driver.execute(
                    null,
                    """
                        INSERT INTO transaction_version(
                          version_id, transaction_id, ledger_id, version_number, posting_set_id,
                          occurred_at, statistics_at, effective_at, note
                        ) VALUES (
                          'version-v10', 'transaction-v10', 'ledger-v10', 1, 'posting-set-v10',
                          '2026-04-28T10:00:00Z', '2026-04-28T10:00:00Z', '2026-04-28T10:00:00Z', NULL
                        )
                    """.trimIndent(),
                    0,
                )
                driver.execute(null, "INSERT INTO ledger_transaction_current_version VALUES ('transaction-v10', 'ledger-v10', 'version-v10')", 0)
                driver.execute(null, "INSERT INTO posting VALUES ('expense-v10', 'posting-set-v10', 'ledger-v10', 0, 'expense-service', 1000, 'CNY', 2)", 0)
                driver.execute(null, "INSERT INTO posting VALUES ('asset-v10', 'posting-set-v10', 'ledger-v10', 1, 'asset-bank', -1000, 'CNY', 2)", 0)
                driver.execute(null, "INSERT INTO rg06_operation(ledger_id, identity_value, action_type) VALUES ('ledger-v10', 'identity-v10', 'create_staged_payment')", 0)
                driver.execute(null, "INSERT INTO rg06_relation VALUES ('ledger-v10', 'relation-v10', 'staged_payment', '{}')", 0)
                driver.execute(
                    null,
                    """
                        INSERT INTO rg06_installment(
                          ledger_id, relation_id, installment_index, payment_id, payment_role,
                          amount_minor, currency_code, currency_precision, funding_account_id,
                          transaction_id, transaction_version_id, posting_set_id, expense_posting_id,
                          asset_posting_id, actual_payment_at, statistics_at, source_payment_at,
                          source_payment_at_text
                        ) VALUES (
                          'ledger-v10', 'relation-v10', 0, 'payment-v10', 'DEPOSIT',
                          1000, 'CNY', 2, 'asset-bank', 'transaction-v10', 'version-v10',
                          'posting-set-v10', 'expense-v10', 'asset-v10',
                          '2026-04-28T10:00:00Z', '2026-04-28T10:00:00Z', NULL, NULL
                        )
                    """.trimIndent(),
                    0,
                )
                driver.execute(
                    null,
                    """
                        INSERT INTO rg06_confirmation(
                          ledger_id, confirmation_id, identity_value, confirmation_kind,
                          candidate_id, relation_id, payment_id, payment_role, category_id, funding_account_id
                        ) VALUES (
                          'ledger-v10', 'confirmation-v10', 'identity-v10', 'MANUAL_INSTALLMENT',
                          NULL, 'relation-v10', 'payment-v10', 'DEPOSIT', 'expense-service', 'asset-bank'
                        )
                    """.trimIndent(),
                    0,
                )
            }

            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, oldVersion = 10, newVersion = 11)
            }

            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                assertEquals(
                    1L,
                    queryCount(
                        driver,
                        "SELECT count(*) FROM pragma_table_info('rg06_confirmation') WHERE name = 'confirmed_at'",
                    ),
                )
                assertEquals(
                    1L,
                    queryCount(driver, "SELECT count(*) FROM rg06_confirmation WHERE confirmed_at IS NULL"),
                )
            }

            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                val confirmed = database.ledgerQueries
                    .selectRg06ConfirmationForIdentity("ledger-v10", "identity-v10")
                    .executeAsOne()
                assertEquals(null, confirmed.confirmed_at)
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun versionElevenToTwelvePreservesFormalRowsAndCreatesEmptyRg09OwnersAcrossReopen() {
        val path = Files.createTempFile("ledger-data-v11-v12-rg09-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 11)
                val database = LedgerDatabase(driver)
                assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countVersions().executeAsOne())
                assertEquals(2L, database.ledgerQueries.countPostings().executeAsOne())
            }

            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, oldVersion = 11, newVersion = 12)
            }

            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                assertEquals(17, LedgerDatabase.Schema.version)
                assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countVersions().executeAsOne())
                assertEquals(2L, database.ledgerQueries.countPostings().executeAsOne())
                assertEquals(0L, database.ledgerQueries.countRg09Operations("ledger-a").executeAsOne())
                assertEquals("1", database.ledgerQueries.foreignKeysEnabled().executeAsOne())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun versionElevenToTwelveDdlFailureRollsBackEveryRg09Owner() {
        val path = Files.createTempFile("ledger-data-v11-v12-rg09-rollback-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 11)
                driver.execute(null, "CREATE TABLE rg09_candidate(blocker TEXT)", 0)
                assertFailsWith<SQLException> {
                    LedgerDatabase(driver).transaction {
                        LedgerDatabase.Schema.migrate(driver, oldVersion = 11, newVersion = 12)
                    }
                }
            }
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT name FROM sqlite_master WHERE name LIKE 'rg09_%' ORDER BY name",
                    ).use { rows ->
                        val names = buildList { while (rows.next()) add(rows.getString(1)) }
                        assertEquals(listOf("rg09_candidate"), names)
                    }
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun versionTwelveToThirteenPreservesFormalRowsAndCreatesEmptyRg10OwnersAcrossReopen() {
        val path = Files.createTempFile("ledger-data-v12-v13-rg10-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 12)
                val database = LedgerDatabase(driver)
                assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countVersions().executeAsOne())
                assertEquals(2L, database.ledgerQueries.countPostings().executeAsOne())
                assertEquals(
                    0L,
                    queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE name LIKE 'rg10_%'"),
                )
            }

            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, oldVersion = 12, newVersion = 13)
            }

            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                assertEquals(17, LedgerDatabase.Schema.version)
                assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countVersions().executeAsOne())
                assertEquals(2L, database.ledgerQueries.countPostings().executeAsOne())
                assertEquals(0L, database.ledgerQueries.countRg09Operations("ledger-a").executeAsOne())
                assertEquals(0L, database.ledgerQueries.countRg10Operations("ledger-a").executeAsOne())
                assertEquals("1", database.ledgerQueries.foreignKeysEnabled().executeAsOne())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun versionFourteenToFifteenPreservesFormalRowsAndCreatesEmptyRg08OwnersAcrossReopen() {
        val path = Files.createTempFile("ledger-data-v14-v15-rg08-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 14)
                val database = LedgerDatabase(driver)
                assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countVersions().executeAsOne())
                assertEquals(2L, database.ledgerQueries.countPostings().executeAsOne())
                assertEquals(
                    0L,
                    queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE name LIKE 'rg08_%'"),
                )
            }

            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, oldVersion = 14, newVersion = 15)
            }

            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                assertEquals(17, LedgerDatabase.Schema.version)
                assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countVersions().executeAsOne())
                assertEquals(2L, database.ledgerQueries.countPostings().executeAsOne())
                assertEquals(0L, database.ledgerQueries.countRg08Operations("ledger-a").executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg08FormalTransactions("ledger-a").executeAsOne())
                assertEquals("1", database.ledgerQueries.foreignKeysEnabled().executeAsOne())
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM pragma_foreign_key_check"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun versionFourteenToFifteenDdlFailureRollsBackEveryRg08Owner() {
        val path = Files.createTempFile("ledger-data-v14-v15-rg08-rollback-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 14)
                driver.execute(null, "CREATE TABLE rg08_position(blocker TEXT)", 0)
                assertFailsWith<SQLException> {
                    LedgerDatabase(driver).transaction {
                        LedgerDatabase.Schema.migrate(driver, oldVersion = 14, newVersion = 15)
                    }
                }
            }
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT name FROM sqlite_master WHERE name LIKE 'rg08_%' ORDER BY name",
                    ).use { rows ->
                        val names = buildList { while (rows.next()) add(rows.getString(1)) }
                        assertEquals(listOf("rg08_position"), names)
                    }
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun versionFifteenToSixteenPreservesFormalRowsAndCreatesEmptyRg11OwnersAcrossReopen() {
        val path = Files.createTempFile("ledger-data-v15-v16-rg11-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 15)
                val database = LedgerDatabase(driver)
                assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countVersions().executeAsOne())
                assertEquals(2L, database.ledgerQueries.countPostings().executeAsOne())
                assertEquals(
                    0L,
                    queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE name LIKE 'rg11_%'"),
                )
            }

            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, oldVersion = 15, newVersion = 16)
            }

            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                assertEquals(17, LedgerDatabase.Schema.version)
                assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countVersions().executeAsOne())
                assertEquals(2L, database.ledgerQueries.countPostings().executeAsOne())
                assertEquals(0L, database.ledgerQueries.countRg11Operations("ledger-a").executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg11FormalTransactions("ledger-a").executeAsOne())
                assertEquals(0L, database.ledgerQueries.selectRg11AllSchedules("ledger-a").executeAsList().size.toLong())
                assertEquals(0L, database.ledgerQueries.selectRg11AllRevisions("ledger-a").executeAsList().size.toLong())
                assertEquals(0L, database.ledgerQueries.selectRg11AllInstallments("ledger-a").executeAsList().size.toLong())
                assertEquals(0L, database.ledgerQueries.selectRg11AllConfirmations("ledger-a").executeAsList().size.toLong())
                assertEquals(0L, database.ledgerQueries.selectRg11AllAuditLinks("ledger-a").executeAsList().size.toLong())
                assertEquals("1", database.ledgerQueries.foreignKeysEnabled().executeAsOne())
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM pragma_foreign_key_check"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun versionFifteenToSixteenDdlFailureRollsBackEveryRg11Owner() {
        val path = Files.createTempFile("ledger-data-v15-v16-rg11-rollback-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 15)
                driver.execute(null, "CREATE TABLE rg11_schedule(blocker TEXT)", 0)
                assertFailsWith<SQLException> {
                    LedgerDatabase(driver).transaction {
                        LedgerDatabase.Schema.migrate(driver, oldVersion = 15, newVersion = 16)
                    }
                }
            }
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT name FROM sqlite_master WHERE name LIKE 'rg11_%' ORDER BY name",
                    ).use { rows ->
                        val names = buildList { while (rows.next()) add(rows.getString(1)) }
                        assertEquals(listOf("rg11_schedule"), names)
                    }
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun versionSixteenToSeventeenPreservesFormalRowsAndCreatesEmptyRg12OwnersAcrossReopen() {
        val path = Files.createTempFile("ledger-data-v16-v17-rg12-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 16)
                val database = LedgerDatabase(driver)
                assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countVersions().executeAsOne())
                assertEquals(2L, database.ledgerQueries.countPostings().executeAsOne())
                assertEquals(
                    0L,
                    queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE name LIKE 'rg12_%'"),
                )
                // The shared confirmation_id column added by RG-11 (v16) is present.
                assertEquals(
                    1L,
                    queryCount(
                        driver,
                        "SELECT count(*) FROM pragma_table_info('transaction_version') WHERE name = 'confirmation_id'",
                    ),
                )
                // A corrected PREPAID_RECOGNITION v2 version carrying the write-once
                // confirmation id (RG-11 ownership) survives the migration untouched.
                database.ledgerQueries.insertTransaction("transaction-recognition-v16", "ledger-a", "PREPAID_RECOGNITION")
                driver.execute(null, "INSERT INTO posting_set(posting_set_id, ledger_id) VALUES ('posting-set-recognition', 'ledger-a')", 0)
                driver.execute(
                    null,
                    """
                        INSERT INTO transaction_version(
                          version_id, transaction_id, ledger_id, version_number, posting_set_id,
                          occurred_at, statistics_at, effective_at, note
                        ) VALUES ('version-recognition-v1', 'transaction-recognition-v16', 'ledger-a', 1,
                          'posting-set-recognition', '2026-01-15T00:30:00Z', '2026-01-15T00:30:00Z',
                          '2026-01-15T00:30:00Z', NULL)
                    """.trimIndent(),
                    0,
                )
                driver.execute(
                    null,
                    "INSERT INTO ledger_transaction_current_version VALUES ('transaction-recognition-v16', 'ledger-a', 'version-recognition-v1')",
                    0,
                )
                driver.execute(
                    null,
                    """
                        INSERT INTO transaction_version(
                          version_id, transaction_id, ledger_id, version_number, posting_set_id,
                          occurred_at, statistics_at, effective_at, note, confirmation_id
                        ) VALUES ('version-recognition-v2', 'transaction-recognition-v16', 'ledger-a', 2,
                          'posting-set-recognition', '2026-01-15T00:30:00Z', '2026-01-15T00:30:00Z',
                          '2026-01-15T00:30:00Z', NULL, 'confirmation-recognition-v16')
                    """.trimIndent(),
                    0,
                )
            }

            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, oldVersion = 16, newVersion = 17)
            }

            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                assertEquals(17, LedgerDatabase.Schema.version)
                // Formal rows of both v1 owners and the v16 RG-11 rows are preserved.
                assertEquals(2L, database.ledgerQueries.countTransactions().executeAsOne())
                assertEquals(3L, database.ledgerQueries.countVersions().executeAsOne())
                assertEquals(2L, database.ledgerQueries.countPostings().executeAsOne())
                assertEquals(2L, database.ledgerQueries.countRg11FormalTransactions("ledger-a").executeAsOne())
                assertEquals(2L, database.ledgerQueries.countRg12FormalTransactions("ledger-a").executeAsOne())
                // Every rg12 owner exists and starts empty.
                assertEquals(0L, database.ledgerQueries.countRg12Operations("ledger-a").executeAsOne())
                assertEquals(0L, database.ledgerQueries.selectRg12AllPostingSemantics("ledger-a").executeAsList().size.toLong())
                assertEquals(0L, database.ledgerQueries.selectRg12AllMatches("ledger-a").executeAsList().size.toLong())
                assertEquals(0L, database.ledgerQueries.selectRg12AllPostingReconciliations("ledger-a").executeAsList().size.toLong())
                assertEquals(0L, database.ledgerQueries.selectRg12AllPostingReplacements("ledger-a").executeAsList().size.toLong())
                assertEquals(0L, database.ledgerQueries.selectRg12AllConfirmations("ledger-a").executeAsList().size.toLong())
                assertEquals(0L, database.ledgerQueries.selectRg12AllConsumptionRecords("ledger-a").executeAsList().size.toLong())
                assertEquals(0L, database.ledgerQueries.selectRg12AllReportPeriods("ledger-a").executeAsList().size.toLong())
                assertEquals(0L, database.ledgerQueries.selectRg12FormalTransactionMetadata("ledger-a").executeAsList().size.toLong())
                assertEquals(0L, database.ledgerQueries.selectRg12TransactionVersionMetadata("ledger-a").executeAsList().size.toLong())
                assertEquals("1", database.ledgerQueries.foreignKeysEnabled().executeAsOne())
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM pragma_foreign_key_check"))
                // The RG-11 write-once confirmation id survives the guard extension.
                assertEquals(
                    1L,
                    queryCount(
                        driver,
                        """
                            SELECT count(*) FROM transaction_version
                            WHERE version_id = 'version-recognition-v2'
                              AND confirmation_id = 'confirmation-recognition-v16'
                        """.trimIndent(),
                    ),
                )
                // The extended v17 guard owns the RG-12 case: a later EXPENSE version may
                // carry the write-once confirmation id (frozen root-correction-transaction-v2).
                driver.execute(
                    null,
                    """
                        INSERT INTO transaction_version(
                          version_id, transaction_id, ledger_id, version_number, posting_set_id,
                          occurred_at, statistics_at, effective_at, note, confirmation_id
                        ) VALUES ('version-existing-v2', 'tx-existing', 'ledger-a', 2,
                          'posting-set-existing', '2026-01-15T00:30:00Z', '2026-01-15T00:30:00Z',
                          '2026-01-15T00:30:00Z', NULL, 'confirmation-expense-v17')
                    """.trimIndent(),
                    0,
                )
                // The write-once rule still holds for the EXPENSE ownership.
                assertFailsWith<SQLException> {
                    driver.execute(
                        null,
                        """
                            UPDATE transaction_version SET confirmation_id = 'confirmation-expense-v17-again'
                            WHERE version_id = 'version-existing-v2'
                        """.trimIndent(),
                        0,
                    )
                }
                // PREPAID_PURCHASE stays outside the confirmation ownership.
                database.ledgerQueries.insertTransaction("transaction-purchase-v17", "ledger-a", "PREPAID_PURCHASE")
                driver.execute(
                    null,
                    """
                        INSERT INTO transaction_version(
                          version_id, transaction_id, ledger_id, version_number, posting_set_id,
                          occurred_at, statistics_at, effective_at, note
                        ) VALUES ('version-purchase-v1', 'transaction-purchase-v17', 'ledger-a', 1,
                          'posting-set-existing', '2026-01-15T00:30:00Z', '2026-01-15T00:30:00Z',
                          '2026-01-15T00:30:00Z', NULL)
                    """.trimIndent(),
                    0,
                )
                assertFailsWith<SQLException> {
                    driver.execute(
                        null,
                        """
                            INSERT INTO transaction_version(
                              version_id, transaction_id, ledger_id, version_number, posting_set_id,
                              occurred_at, statistics_at, effective_at, note, confirmation_id
                            ) VALUES ('version-purchase-v2', 'transaction-purchase-v17', 'ledger-a', 2,
                              'posting-set-existing', '2026-01-15T00:30:00Z', '2026-01-15T00:30:00Z',
                              '2026-01-15T00:30:00Z', NULL, 'confirmation-purchase-v17')
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
    fun versionSixteenToSeventeenDdlFailureRollsBackEveryRg12Owner() {
        val path = Files.createTempFile("ledger-data-v16-v17-rg12-rollback-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 16)
                driver.execute(null, "CREATE TABLE rg12_operation(blocker TEXT)", 0)
                assertFailsWith<SQLException> {
                    LedgerDatabase(driver).transaction {
                        LedgerDatabase.Schema.migrate(driver, oldVersion = 16, newVersion = 17)
                    }
                }
            }
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT name FROM sqlite_master WHERE name LIKE 'rg12_%' ORDER BY name",
                    ).use { rows ->
                        val names = buildList { while (rows.next()) add(rows.getString(1)) }
                        assertEquals(listOf("rg12_operation"), names)
                    }
                    // The transaction rollback restored the dropped RG-11 guards: the
                    // shared confirmation guard is still in place at v16 semantics.
                    statement.executeQuery(
                        "SELECT count(*) FROM sqlite_master WHERE name = 'rg11_transaction_version_confirmation_guard_insert'",
                    ).use { rows ->
                        check(rows.next())
                        assertEquals(1L, rows.getLong(1))
                    }
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun versionSixteenWiresPrepaidCanonicalKindsAndProtectsRg11Guards() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            database.ledgerQueries.insertTransaction("transaction-purchase-v16", "ledger-a", "PREPAID_PURCHASE")
            database.ledgerQueries.insertTransaction("transaction-recognition-v16", "ledger-a", "PREPAID_RECOGNITION")
            val stored = driver.executeQuery(
                identifier = null,
                sql = "SELECT transaction_id, kind, canonical_kind FROM ledger_transaction ORDER BY transaction_id",
                mapper = { cursor ->
                    val rows = buildList {
                        while (cursor.next().value) {
                            add(
                                listOf(
                                    requireNotNull(cursor.getString(0)),
                                    requireNotNull(cursor.getString(1)),
                                    requireNotNull(cursor.getString(2)),
                                ),
                            )
                        }
                    }
                    app.cash.sqldelight.db.QueryResult.Value(rows)
                },
                parameters = 0,
            ).value
            assertEquals(
                listOf(
                    listOf("transaction-purchase-v16", "EXPENSE", "PREPAID_PURCHASE"),
                    listOf("transaction-recognition-v16", "EXPENSE", "PREPAID_RECOGNITION"),
                ),
                stored,
            )
            // The shared confirmation_id column is write-once and reserved for later
            // versions of PREPAID_RECOGNITION transactions.
            driver.execute(
                null,
                """
                    INSERT INTO posting_set(posting_set_id, ledger_id) VALUES ('posting-set-v16', 'ledger-a')
                """.trimIndent(),
                0,
            )
            driver.execute(
                null,
                """
                    INSERT INTO transaction_version(
                      version_id, transaction_id, ledger_id, version_number, posting_set_id,
                      occurred_at, statistics_at, effective_at, note
                    ) VALUES ('version-recognition-v1', 'transaction-recognition-v16', 'ledger-a', 1,
                      'posting-set-v16', '2026-01-15T00:30:00Z', '2026-01-15T00:30:00Z',
                      '2026-01-15T00:30:00Z', NULL)
                """.trimIndent(),
                0,
            )
            driver.execute(
                null,
                """
                    INSERT INTO ledger_transaction_current_version
                    VALUES ('transaction-recognition-v16', 'ledger-a', 'version-recognition-v1')
                """.trimIndent(),
                0,
            )
            assertFailsWith<SQLException> {
                driver.execute(
                    null,
                    """
                        UPDATE transaction_version SET confirmation_id = 'confirmation-smoke'
                        WHERE version_id = 'version-recognition-v1'
                    """.trimIndent(),
                    0,
                )
            }
            assertFailsWith<SQLException> {
                driver.execute(
                    null,
                    """
                        INSERT INTO transaction_version(
                          version_id, transaction_id, ledger_id, version_number, posting_set_id,
                          occurred_at, statistics_at, effective_at, note, confirmation_id
                        ) VALUES ('version-bad-v16', 'transaction-purchase-v16', 'ledger-a', 2,
                          'posting-set-existing', '2026-01-15T00:30:00Z', '2026-01-15T00:30:00Z',
                          '2026-01-15T00:30:00Z', NULL, 'confirmation-bad-v16')
                    """.trimIndent(),
                    0,
                )
            }
            // A schedule cannot own a transaction that is not PREPAID_PURCHASE.
            assertFailsWith<SQLException> {
                driver.execute(
                    null,
                    """
                        INSERT INTO rg11_schedule(
                          ledger_id, schedule_id, payment_transaction_id, prepaid_account_id,
                          category_id, total_amount_minor, currency_code, currency_precision,
                          cadence, start_at, anchor_kind, anchor_day
                        ) VALUES ('ledger-a', 'schedule-bad-v16', 'transaction-recognition-v16',
                          'prepaid-account', 'category', 10000, 'CNY', 2, 'MONTHLY',
                          '2026-01-15T00:30:00Z', 'MONTH_END', NULL)
                    """.trimIndent(),
                    0,
                )
            }
            // Operation rows are immutable even when callers bypass the store.
            database.ledgerQueries.insertRg11Operation(
                "ledger-a",
                "request-smoke",
                "create_periodic_allocation",
                "create_periodic_allocation",
                "fingerprint-smoke",
                "PENDING",
                null,
                null,
            )
            assertFailsWith<SQLException> {
                driver.execute(
                    null,
                    "DELETE FROM rg11_operation WHERE ledger_id = 'ledger-a' AND identity_value = 'request-smoke'",
                    0,
                )
            }
            database.ledgerQueries.updateRg11OperationResult("ACCEPTED", null, null, "ledger-a", "request-smoke")
            assertFailsWith<SQLException> {
                driver.execute(
                    null,
                    "DELETE FROM rg11_operation WHERE ledger_id = 'ledger-a' AND identity_value = 'request-smoke'",
                    0,
                )
            }
        } finally {
            driver.close()
        }
    }

    @Test
    fun versionFifteenWiresLendCollectCanonicalKindsAndProtectsRg08Guards() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            database.ledgerQueries.insertTransaction("transaction-lend-v15", "ledger-a", "LEND")
            database.ledgerQueries.insertTransaction("transaction-collect-v15", "ledger-a", "COLLECT")
            val stored = driver.executeQuery(
                identifier = null,
                sql = "SELECT transaction_id, kind, canonical_kind FROM ledger_transaction ORDER BY transaction_id",
                mapper = { cursor ->
                    val rows = buildList {
                        while (cursor.next().value) {
                            add(
                                listOf(
                                    requireNotNull(cursor.getString(0)),
                                    requireNotNull(cursor.getString(1)),
                                    requireNotNull(cursor.getString(2)),
                                ),
                            )
                        }
                    }
                    app.cash.sqldelight.db.QueryResult.Value(rows)
                },
                parameters = 0,
            ).value
            assertEquals(
                listOf(
                    listOf("transaction-collect-v15", "EXPENSE", "COLLECT"),
                    listOf("transaction-lend-v15", "EXPENSE", "LEND"),
                ),
                stored,
            )
            // A valid position row with its first lend history entry.
            driver.execute(
                null,
                """
                    INSERT INTO rg08_position(
                      ledger_id, position_id, counterparty_id, receivable_account_id,
                      currency_code, currency_precision, principal_balance_minor,
                      allocation_scope, contract_allocation_enabled
                    ) VALUES ('ledger-a', 'position-smoke', 'counterparty-smoke', 'receivable-smoke',
                      'CNY', 2, 10000, 'PERSON_LEVEL_NET_POSITION', 0)
                """.trimIndent(),
                0,
            )
            driver.execute(
                null,
                """
                    INSERT INTO rg08_position_history(
                      ledger_id, position_id, history_sequence, history_id, behavior_code,
                      amount_minor, principal_balance_after_minor, transaction_id, occurred_at
                    ) VALUES ('ledger-a', 'position-smoke', 1, 'history-smoke', 'LEND',
                      10000, 10000, 'transaction-lend-v15', '2026-02-01T09:00:00+08:00')
                """.trimIndent(),
                0,
            )
            // The current projection cannot move without a matching newest collect history row.
            assertFailsWith<SQLException> {
                driver.execute(
                    null,
                    "UPDATE rg08_position SET principal_balance_minor = 9000 WHERE ledger_id = 'ledger-a' AND position_id = 'position-smoke'",
                    0,
                )
            }
            // History is strictly sequenced: a gap is rejected.
            assertFailsWith<SQLException> {
                driver.execute(
                    null,
                    """
                        INSERT INTO rg08_position_history(
                          ledger_id, position_id, history_sequence, history_id, behavior_code,
                          amount_minor, principal_balance_after_minor, transaction_id, occurred_at
                        ) VALUES ('ledger-a', 'position-smoke', 3, 'history-gap', 'COLLECT',
                          -1000, 9000, 'transaction-collect-v15', '2026-02-02T09:00:00+08:00')
                    """.trimIndent(),
                    0,
                )
            }
            // A lend history entry cannot be negative.
            assertFailsWith<SQLException> {
                driver.execute(
                    null,
                    """
                        INSERT INTO rg08_position_history(
                          ledger_id, position_id, history_sequence, history_id, behavior_code,
                          amount_minor, principal_balance_after_minor, transaction_id, occurred_at
                        ) VALUES ('ledger-a', 'position-smoke', 2, 'history-direction', 'LEND',
                          -1000, 9000, 'transaction-lend-v15', '2026-02-02T09:00:00+08:00')
                    """.trimIndent(),
                    0,
                )
            }
            // Position and operation rows are immutable even when callers bypass the store.
            assertFailsWith<SQLException> {
                driver.execute(
                    null,
                    "UPDATE rg08_position SET counterparty_id = 'other' WHERE ledger_id = 'ledger-a' AND position_id = 'position-smoke'",
                    0,
                )
            }
            assertFailsWith<SQLException> {
                driver.execute(
                    null,
                    "DELETE FROM rg08_position WHERE ledger_id = 'ledger-a' AND position_id = 'position-smoke'",
                    0,
                )
            }
            database.ledgerQueries.insertRg08Operation(
                "ledger-a",
                "request-smoke",
                "validate_lending_event",
                "validate_lending_event",
                "fingerprint-smoke",
                "PENDING",
                null,
                null,
            )
            assertFailsWith<SQLException> {
                driver.execute(
                    null,
                    "DELETE FROM rg08_operation WHERE ledger_id = 'ledger-a' AND identity_value = 'request-smoke'",
                    0,
                )
            }
            database.ledgerQueries.updateRg08OperationResult("ACCEPTED", null, null, "ledger-a", "request-smoke")
            assertFailsWith<SQLException> {
                driver.execute(
                    null,
                    "DELETE FROM rg08_operation WHERE ledger_id = 'ledger-a' AND identity_value = 'request-smoke'",
                    0,
                )
            }
        } finally {
            driver.close()
        }
    }

    @Test
    fun populatedVersionThirteenRemovesOnlyDerivedAdjustmentColumnsAtVersionFourteen() {
        val path = Files.createTempFile("ledger-data-v13-v14-rg09-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 13)
                insertVersionThirteenAdjustment(driver, explainedAmountMinor = 1_000)
                LedgerDatabase(driver).transaction {
                    LedgerDatabase.Schema.migrate(driver, oldVersion = 13, newVersion = 14)
                }
            }

            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                assertEquals(
                    0L,
                    queryCount(
                        driver,
                        """
                            SELECT count(*) FROM pragma_table_info('rg09_balance_adjustment')
                            WHERE name IN ('explained_amount_minor', 'remaining_amount_minor', 'state')
                        """.trimIndent(),
                    ),
                )
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM rg09_balance_adjustment"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM rg09_allocation"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM rg09_adjustment_history"))
                assertEquals(
                    1L,
                    queryCount(
                        driver,
                        """
                            SELECT count(*)
                            FROM rg09_balance_adjustment AS adjustment
                            JOIN rg09_adjustment_history AS history
                              ON history.ledger_id = adjustment.ledger_id
                             AND history.adjustment_id = adjustment.adjustment_id
                            WHERE adjustment.adjustment_id = 'adjustment-v13'
                              AND adjustment.transaction_id = 'transaction-adjustment-v13'
                              AND adjustment.observation_id = 'observation-v13'
                              AND adjustment.original_delta_minor = 3000
                              AND history.history_id = 'history-v13'
                              AND history.remaining_amount_minor = 2000
                              AND history.state = 'PARTIALLY_EXPLAINED'
                              AND 1000 = (
                                SELECT SUM(allocation.amount_minor)
                                FROM rg09_allocation AS allocation
                                WHERE allocation.ledger_id = adjustment.ledger_id
                                  AND allocation.adjustment_id = adjustment.adjustment_id
                              )
                        """.trimIndent(),
                    ),
                )
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM pragma_foreign_key_check"))
                assertEquals("1", LedgerDatabase(driver).ledgerQueries.foreignKeysEnabled().executeAsOne())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun versionThirteenProjectionMismatchRejectsMigrationAndRollsBackAtomically() {
        val path = Files.createTempFile("ledger-data-v13-v14-rg09-rollback-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 13)
                insertVersionThirteenAdjustment(driver, explainedAmountMinor = 0)
                assertFailsWith<SQLException> {
                    LedgerDatabase(driver).transaction {
                        LedgerDatabase.Schema.migrate(driver, oldVersion = 13, newVersion = 14)
                    }
                }
            }

            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                assertEquals(
                    3L,
                    queryCount(
                        driver,
                        """
                            SELECT count(*) FROM pragma_table_info('rg09_balance_adjustment')
                            WHERE name IN ('explained_amount_minor', 'remaining_amount_minor', 'state')
                        """.trimIndent(),
                    ),
                )
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM rg09_balance_adjustment"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM rg09_allocation"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM rg09_adjustment_history"))
                assertEquals(
                    0L,
                    queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE name = 'rg09_v14_migration_guard'"),
                )
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM pragma_foreign_key_check"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun versionThirteenLatestHistoryMismatchRejectsMigrationAndRollsBackAtomically() {
        val path = Files.createTempFile("ledger-data-v13-v14-rg09-history-rollback-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 13)
                // The stored adjustment projections derive cleanly from the
                // allocations (explained 1000 / remaining 2000 /
                // PARTIALLY_EXPLAINED), but the latest history row disagrees
                // with the stored remaining amount, so the guard's latest
                // history matching branch must reject the migration.
                insertVersionThirteenAdjustment(
                    driver,
                    explainedAmountMinor = 1_000,
                    historyRemainingAmountMinor = 1_500,
                )
                assertFailsWith<SQLException> {
                    LedgerDatabase(driver).transaction {
                        LedgerDatabase.Schema.migrate(driver, oldVersion = 13, newVersion = 14)
                    }
                }
            }

            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                assertEquals(
                    3L,
                    queryCount(
                        driver,
                        """
                            SELECT count(*) FROM pragma_table_info('rg09_balance_adjustment')
                            WHERE name IN ('explained_amount_minor', 'remaining_amount_minor', 'state')
                        """.trimIndent(),
                    ),
                )
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM rg09_balance_adjustment"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM rg09_allocation"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM rg09_adjustment_history"))
                assertEquals(
                    0L,
                    queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE name = 'rg09_v14_migration_guard'"),
                )
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM pragma_foreign_key_check"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun versionThirteenMissingHistoryRejectsMigrationAndRollsBackAtomically() {
        val path = Files.createTempFile("ledger-data-v13-v14-rg09-nohistory-rollback-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 13)
                // The stored adjustment projections derive cleanly, but the
                // adjustment has no history row at all, so the guard's
                // NOT EXISTS latest-history branch must reject the migration.
                insertVersionThirteenAdjustment(
                    driver,
                    explainedAmountMinor = 1_000,
                    includeHistory = false,
                )
                assertFailsWith<SQLException> {
                    LedgerDatabase(driver).transaction {
                        LedgerDatabase.Schema.migrate(driver, oldVersion = 13, newVersion = 14)
                    }
                }
            }

            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                assertEquals(
                    3L,
                    queryCount(
                        driver,
                        """
                            SELECT count(*) FROM pragma_table_info('rg09_balance_adjustment')
                            WHERE name IN ('explained_amount_minor', 'remaining_amount_minor', 'state')
                        """.trimIndent(),
                    ),
                )
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM rg09_balance_adjustment"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM rg09_allocation"))
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM rg09_adjustment_history"))
                assertEquals(
                    0L,
                    queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE name = 'rg09_v14_migration_guard'"),
                )
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM pragma_foreign_key_check"))
            }
        } finally {
            Files.deleteIfExists(path)
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
    fun freshVersionSeventeenAndMigratedVersionOneHaveEquivalentSchemaMetadata() {
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
                LedgerDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 17)
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

private fun insertVersionThirteenAdjustment(
    driver: JdbcSqliteDriver,
    explainedAmountMinor: Long,
    includeHistory: Boolean = true,
    historyRemainingAmountMinor: Long = 3_000 - explainedAmountMinor,
    historyState: String = if (explainedAmountMinor == 0L) "OPEN" else "PARTIALLY_EXPLAINED",
) {
    driver.execute(
        null,
        """
            INSERT INTO ledger_transaction(transaction_id, ledger_id, kind, canonical_kind) VALUES
              ('transaction-adjustment-v13', 'ledger-v13', 'ACCOUNT_TRANSFER', 'BALANCE_ADJUSTMENT'),
              ('transaction-real-v13', 'ledger-v13', 'ACCOUNT_TRANSFER', 'ACCOUNT_TRANSFER'),
              ('transaction-reversal-v13', 'ledger-v13', 'ACCOUNT_TRANSFER', 'BALANCE_ADJUSTMENT_REVERSAL')
        """.trimIndent(),
        0,
    )
    driver.execute(
        null,
        """
            INSERT INTO rg09_source(
              ledger_id, source_id, source_type, observed_at, observed_at_text, account_id,
              amount_minor, currency_code, currency_precision, immutable_payload_digest
            ) VALUES (
              'ledger-v13', 'source-v13', 'BALANCE_OBSERVATION', '2026-01-31T15:59:59Z',
              '2026-01-31T23:59:59+08:00', 'asset-v13', 13000, 'CNY', 2, 'digest-v13'
            )
        """.trimIndent(),
        0,
    )
    driver.execute(
        null,
        """
            INSERT INTO rg09_observation(
              ledger_id, observation_id, source_id, account_id, target_amount_minor,
              currency_code, currency_precision, target_observed_at, target_observed_at_text,
              saved_at, saved_at_text
            ) VALUES (
              'ledger-v13', 'observation-v13', 'source-v13', 'asset-v13', 13000, 'CNY', 2,
              '2026-01-31T15:59:59Z', '2026-01-31T23:59:59+08:00',
              '2026-02-01T00:00:00Z', '2026-02-01T08:00:00+08:00'
            )
        """.trimIndent(),
        0,
    )
    driver.execute(
        null,
        """
            INSERT INTO rg09_balance_adjustment(
              ledger_id, adjustment_id, transaction_id, observation_id, target_account_id,
              equity_account_id, currency_code, currency_precision, target_observed_at,
              target_observed_at_text, replayed_amount_minor, target_amount_minor,
              original_delta_minor, explained_amount_minor, remaining_amount_minor, state
            ) VALUES (
              'ledger-v13', 'adjustment-v13', 'transaction-adjustment-v13', 'observation-v13',
              'asset-v13', 'equity-v13', 'CNY', 2, '2026-01-31T15:59:59Z',
              '2026-01-31T23:59:59+08:00', 10000, 13000, 3000, $explainedAmountMinor,
              ${3_000 - explainedAmountMinor},
              '${if (explainedAmountMinor == 0L) "OPEN" else "PARTIALLY_EXPLAINED"}'
            )
        """.trimIndent(),
        0,
    )
    if (includeHistory) {
        driver.execute(
            null,
            """
                INSERT INTO rg09_adjustment_history(
                  ledger_id, adjustment_id, history_sequence, history_id, state, occurred_at,
                  occurred_at_text, created_at, created_at_text, allocation_id, remaining_amount_minor
                ) VALUES (
                  'ledger-v13', 'adjustment-v13', 1, 'history-v13',
                  '$historyState',
                  '2026-02-01T00:00:00Z', '2026-02-01T08:00:00+08:00',
                  '2026-02-01T00:00:00Z', '2026-02-01T08:00:00+08:00',
                  'allocation-v13', $historyRemainingAmountMinor
                )
            """.trimIndent(),
            0,
        )
    }
    driver.execute(
        null,
        """
            INSERT INTO rg09_allocation(
              ledger_id, allocation_id, adjustment_id, target_account_id, amount_minor,
              currency_code, currency_precision, real_transaction_id, reversal_transaction_id,
              confirmed_at, discovered_at, discovered_at_text, confirmed_at_text,
              created_at, created_at_text
            ) VALUES (
              'ledger-v13', 'allocation-v13', 'adjustment-v13', 'asset-v13', 1000, 'CNY', 2,
              'transaction-real-v13', 'transaction-reversal-v13', '2026-02-01T00:00:00Z',
              '2026-01-30T00:00:00Z', '2026-01-30T08:00:00+08:00',
              '2026-02-01T08:00:00+08:00', '2026-02-01T00:00:00Z',
              '2026-02-01T08:00:00+08:00'
            )
        """.trimIndent(),
        0,
    )
}

private fun migrationRepositoryFile(relative: String): Path {
    var candidate = Path.of(System.getProperty("user.dir"))
    repeat(8) {
        if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate.resolve(relative)
        candidate = candidate.parent ?: error("repository root not found")
    }
    error("repository root not found")
}

private fun queryCount(driver: JdbcSqliteDriver, sql: String): Long = driver.executeQuery(
    null,
    sql,
    { cursor ->
        check(cursor.next().value)
        app.cash.sqldelight.db.QueryResult.Value(requireNotNull(cursor.getLong(0)))
    },
    0,
).value

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
        .replace("\"rg07_operation\"", "rg07_operation")

private fun migrationSqliteProperties(): Properties = Properties().apply {
    setProperty("foreign_keys", "true")
    setProperty("busy_timeout", "5000")
}

internal val VERSION_ONE_STATEMENTS = listOf(
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
    "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('tx-existing', 'ledger-a', 'EXPENSE')",
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
