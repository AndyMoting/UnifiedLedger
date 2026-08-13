package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.data.db.LedgerDatabase
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Rg04SchemaV7Test {
    @Test
    fun freshSchemaOwnsImportLifecycleSeparatelyAtVersionSeven() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            assertEquals(21, LedgerDatabase.Schema.version)
            assertEquals(0L, database.ledgerQueries.countRg04OperationRequests().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countRg04ImportRequests().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countRg04ImportSources().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countRg04ImportEvidence().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countRg04ImportCandidates().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countRg04ImportConfirmations().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countRg04ImportMatches().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countRg04ImportReceipts().executeAsOne())
        } finally {
            driver.close()
        }
    }

    @Test
    fun acceptedImportOwnerCannotBeUpdatedOrDeletedDirectly() {
        val case = decodedCase()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val executor = com.unifiedledger.application.ExecuteRg04ImportOperation(SqlDelightRg04ImportStore(database, driver, case.catalog))
            executor.execute(case.importOperations[0])
            assertFailsWith<SQLException> { driver.execute(null, "UPDATE rg04_import_request SET action_type = 'MERGE_MIRROR'", 0) }
            assertFailsWith<SQLException> { driver.execute(null, "DELETE FROM rg04_import_request", 0) }
            assertFailsWith<SQLException> { driver.execute(null, "UPDATE rg04_import_candidate SET confidence = '0.58'", 0) }
            assertFailsWith<SQLException> { driver.execute(null, "DELETE FROM rg04_import_evidence", 0) }
            assertEquals(1L, database.ledgerQueries.countRg04ImportRequests().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countRg04ImportReceipts().executeAsOne())
        } finally {
            driver.close()
        }
    }
}
