package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.data.db.LedgerDatabase
import java.nio.file.Files
import java.sql.DriverManager
import java.util.Properties
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals

class Rg05SchemaV8Test {
    @Test
    fun versionSevenToEightPreservesExistingOwnersAndAddsRg05Lifecycle() {
        val path = Files.createTempFile("ledger-data-v7-v8-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            val properties = Properties().apply { setProperty("foreign_keys", "true") }
            JdbcSqliteDriver(url, properties).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 1, 7)
                driver.execute(null, "INSERT INTO rg04_operation_request VALUES ('ledger-a','existing','CREDIT_PRINCIPAL_REPAYMENT')", 0)
            }
            JdbcSqliteDriver(url, properties).use { driver -> LedgerDatabase.Schema.migrate(driver, 7, 8) }
            JdbcSqliteDriver(url, properties).use { driver ->
                val database = LedgerDatabase(driver)
                assertEquals(1L, database.ledgerQueries.countRg04OperationRequests().executeAsOne())
                assertEquals(0L, database.ledgerQueries.countRg05OperationRequests().executeAsOne())
                assertEquals(0L, database.ledgerQueries.countRg05Sources().executeAsOne())
                assertEquals(0L, database.ledgerQueries.countRg05Candidates().executeAsOne())
                assertEquals(0L, database.ledgerQueries.countRg05PostingReconciliations().executeAsOne())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
