package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.data.db.LedgerDatabase
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LedgerMigrationV1ToV11Test : MigrationTestSupport() {
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
    fun versionFiveToSixDdlFailureRollsBackEveryStageAndRg04Owner() {
        // MIG-002: 5 -> 6 (5.sqm) drops the core chain and the two rg03 receipt owners,
        // rebuilds them through stage tables and then creates the rg04 owners and their
        // triggers (93 CREATE statements in total, the report's HIGH-risk edge). A
        // same-name object occupying the rg04_operation_request slot makes the migration
        // fail after the whole chain; the wrapped migrate must roll back every staged,
        // dropped and rebuilt object, leak no rg04 trigger and leave the v5 schema and
        // rows untouched.
        val path = Files.createTempFile("ledger-data-v5-v6-rg04-rollback-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 5)
                driver.execute(null, "INSERT INTO rg03_operation_request VALUES ('ledger-a','rg03-existing','MANUAL_ACCOUNT_TRANSFER')", 0)
                driver.execute(null, "CREATE TABLE rg04_operation_request(blocker TEXT)", 0)
                assertFailsWith<SQLException> {
                    LedgerDatabase(driver).transaction {
                        LedgerDatabase.Schema.migrate(driver, oldVersion = 5, newVersion = 6)
                    }
                }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                // The blocker is the only rg04 object: no rg04 table of the aborted
                // migration landed and no stage table survives.
                driver
                    .executeQuery(
                        null,
                        "SELECT name FROM sqlite_master WHERE name LIKE 'rg04_%' ORDER BY name",
                        { cursor ->
                            val names = buildList { while (cursor.next().value) add(requireNotNull(cursor.getString(0))) }
                            app.cash.sqldelight.db.QueryResult
                                .Value(names)
                        },
                        0,
                    ).value
                    .let { assertEquals(listOf("rg04_operation_request"), it) }
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE name LIKE '%_stage'"))
                // No trigger of the aborted migration leaked: the v5 schema has no
                // triggers at all.
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE type='trigger'"))
                // The dropped-and-rebuilt rg03 receipt owners are back at their v5 shape.
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE name = 'rg03_confirmation'"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE name = 'rg03_operation_receipt'"))
                // The seeded v5 rg03 row and the v1 core rows are untouched.
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM rg03_operation_request WHERE request_id = 'rg03-existing'"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM ledger_transaction"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM transaction_version"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM ledger_transaction_current_version"))
                assertEquals(2L, queryCount(driver, "SELECT count(*) FROM posting"))
                // The original v5 ledger_transaction CHECK is restored: CREDIT_REPAYMENT
                // stays rejected until the migration actually commits.
                assertFailsWith<SQLException> {
                    driver.execute(
                        null,
                        "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('tx-credit', 'ledger-a', 'CREDIT_REPAYMENT')",
                        0,
                    )
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
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun populatedVersionFourPreservesIncomeOwnersAndCurrentChainAtVersionFive() {
        val path = Files.createTempFile("ledger-data-v4-v5-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            DriverManager.getConnection(url).use { connection -> connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) } }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 1, 4)
                driver.execute(null, "INSERT INTO manual_income_request(ledger_id, request_id, amount_minor, currency_code, currency_precision, category_id, receiving_account_id, occurred_at, note, confirmation_marker) VALUES ('ledger-a','income-existing',500,'CNY',2,'income-category','asset-bank-a','2026-01-16T00:00:00Z','','explicit_manual_save')", 0)
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
    fun versionThreeToFourDdlFailureRollsBackEveryStageAndCoreTable() {
        // MIG-002: 3 -> 4 (3.sqm) is a full stage/drop/rebuild chain: five stage tables
        // plus the ledger_transaction stage, the core chain dropped and rebuilt, then
        // the manual_income_request / confirmed_income_receipt owners. A same-name
        // object occupying the manual_income_request slot makes the migration fail
        // after the whole chain, so the wrapped migrate must roll back every staged,
        // dropped and rebuilt object and leave the v3 schema and rows untouched.
        val path = Files.createTempFile("ledger-data-v3-v4-rollback-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        var originalLedgerTransactionSql: String? = null
        try {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 3)
                // The v3 ledger_transaction DDL text (created by the v1 base schema and
                // untouched by 2.sqm) is the rollback oracle: the rebuilt v4 table text
                // differs, so a leaked v4 table would fail the text comparison.
                originalLedgerTransactionSql = tableSql(driver, "ledger_transaction")
                driver.execute(null, "CREATE TABLE manual_income_request(blocker TEXT)", 0)
                assertFailsWith<SQLException> {
                    LedgerDatabase(driver).transaction {
                        LedgerDatabase.Schema.migrate(driver, oldVersion = 3, newVersion = 4)
                    }
                }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                // The blocker is the only object of the aborted migration: the second
                // income owner never landed and no stage table survives.
                driver
                    .executeQuery(
                        null,
                        "SELECT name FROM sqlite_master WHERE name IN ('manual_income_request', 'confirmed_income_receipt') ORDER BY name",
                        { cursor ->
                            val names = buildList { while (cursor.next().value) add(requireNotNull(cursor.getString(0))) }
                            app.cash.sqldelight.db.QueryResult
                                .Value(names)
                        },
                        0,
                    ).value
                    .let { assertEquals(listOf("manual_income_request"), it) }
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE name LIKE '%_stage'"))
                // Every core table the migration would have dropped is back in its
                // original v3 shape with the seeded rows untouched: the ledger_transaction
                // DDL text is byte-equal to the pre-migration v3 text.
                assertEquals(originalLedgerTransactionSql, tableSql(driver, "ledger_transaction"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM ledger_transaction"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM transaction_version"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM ledger_transaction_current_version"))
                assertEquals(2L, queryCount(driver, "SELECT count(*) FROM posting"))
                // The v1/v2 owner tables that 3.sqm would have dropped still exist.
                assertEquals(
                    3L,
                    queryCount(
                        driver,
                        """
                        SELECT count(*) FROM sqlite_master WHERE type='table' AND name IN (
                          'confirmed_expense_receipt', 'transaction_note_update_request',
                          'confirmed_transaction_note_update_receipt'
                        )
                        """.trimIndent(),
                    ),
                )
                // Discriminating probe: the original v3 ledger_transaction CHECK rejects
                // INCOME (the v4 rebuilt table would accept it), so a leaked v4 table
                // state would make this insert succeed and fail the test.
                assertFailsWith<SQLException> {
                    driver.execute(
                        null,
                        "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('tx-income', 'ledger-a', 'INCOME')",
                        0,
                    )
                }
            }
        } finally {
            Files.deleteIfExists(path)
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
                // Raw SQL, not the generated insertTransactionVersion: this is a genuine v9
                // surface, which predates statistics_at_epoch_nanos (v32), so the generated
                // statement's column list would not resolve here.
                driver.execute(
                    null,
                    "INSERT INTO transaction_version(version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note) " +
                        "VALUES ('version-v9', 'transaction-v9', 'ledger-v9', 1, 'posting-set-v9', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z', '')",
                    0,
                )
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
                val rg07Table =
                    driver
                        .executeQuery(null, "SELECT name FROM sqlite_master WHERE type='table' AND name='rg07_operation'", { cursor ->
                            cursor.next()
                            app.cash.sqldelight.db.QueryResult
                                .Value(cursor.getString(0))
                        }, 0)
                        .value
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
                val confirmed =
                    database.ledgerQueries
                        .selectRg06ConfirmationForIdentity("ledger-v10", "identity-v10")
                        .executeAsOne()
                assertEquals(null, confirmed.confirmed_at)
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

    /** Seeds a terminal-v25 world holding one confirmed transfer link + v23-shaped snapshot. */

    @Test
    fun freshSchemaAndMigratedVersionOneHaveEquivalentSchemaMetadata() {
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
                LedgerDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 37)
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
                driver.execute(
                    null,
                    """
                    INSERT INTO manual_expense_request(
                      ledger_id, request_id, amount_minor, currency_code, currency_precision,
                      category_id, payment_account_id, occurred_at, note, confirmation_marker
                    ) VALUES ('ledger-a', 'request-expense-existing', 3580, 'CNY', 2,
                      'expense-category-breakfast', 'asset-bank-a', '2026-01-15T00:30:00Z', '',
                      'explicit_manual_save')
                    """.trimIndent(),
                    0,
                )
                driver.execute(
                    null,
                    """
                    INSERT INTO confirmed_expense_receipt(
                      ledger_id, request_id, confirmation_id, transaction_id
                    ) VALUES ('ledger-a', 'request-expense-existing',
                      'confirmation-expense-existing', 'tx-existing')
                    """.trimIndent(),
                    0,
                )
                driver.execute(
                    null,
                    """
                    INSERT INTO transaction_version(
                      version_id, transaction_id, ledger_id, version_number, posting_set_id,
                      occurred_at, statistics_at, effective_at, note
                    ) VALUES ('version-existing-v2', 'tx-existing', 'ledger-a', 2,
                      'posting-set-existing', '2026-01-15T00:30:00Z', '2026-01-15T00:30:00Z',
                      '2026-01-15T00:30:00Z', 'replacement note')
                    """.trimIndent(),
                    0,
                )
                driver.execute(
                    null,
                    """
                    UPDATE ledger_transaction_current_version
                    SET current_version_id = 'version-existing-v2'
                    WHERE transaction_id = 'tx-existing' AND ledger_id = 'ledger-a'
                    """.trimIndent(),
                    0,
                )
                driver.execute(
                    null,
                    """
                    INSERT INTO transaction_note_update_request(
                      ledger_id, request_id, transaction_id, note, confirmation_marker
                    ) VALUES ('ledger-a', 'request-note-existing', 'tx-existing',
                      'replacement note', 'explicit_manual_save')
                    """.trimIndent(),
                    0,
                )
                driver.execute(
                    null,
                    """
                    INSERT INTO confirmed_transaction_note_update_receipt(
                      ledger_id, request_id, confirmation_id, transaction_id, version_id,
                      expected_current_version_id
                    ) VALUES ('ledger-a', 'request-note-existing', 'confirmation-note-existing',
                      'tx-existing', 'version-existing-v2', 'version-existing-v1')
                    """.trimIndent(),
                    0,
                )
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
                assertEquals(
                    "replacement note",
                    database.ledgerQueries
                        .selectCurrentNote()
                        .executeAsOne()
                        .note,
                )
                assertEquals(0, database.ledgerQueries.countManualIncomeRequests().executeAsOne())
                assertEquals(0, database.ledgerQueries.countIncomeReceipts().executeAsOne())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
