package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.*
import com.unifiedledger.data.db.LedgerDatabase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class Rg05RawJsonEndToEndTest {
    @Test
    fun trackedV1ImportLifecycleDecodesAndExecutesWithoutASecondCashFlow() {
        val raw = Files.readString(rg05RepositoryFile("golden/rules/rg-05.json"))
        val case = assertIs<Rg05RawJsonDecodeResult.Success>(decodeRg05RawJson(raw)).value
        assertEquals(3, case.importOperations.size)
        assertIs<Rg05PreparedOperation.Ingest>(case.importOperations[0])
        assertIs<Rg05PreparedOperation.Confirm>(case.importOperations[1])
        assertIs<Rg05PreparedOperation.Receipt>(case.importOperations[2])

        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val executor = ExecuteRg05Operation(SqlDelightRg05Store(database, driver, case.catalog, object : Rg05IdentitySource { override fun manual(requestId: RequestId) = Rg05ManualCommitIds("unused") }))
            assertIs<Rg05ExecutionResult.IngestAccepted>(executor.execute(case.importOperations[0]))
            assertEquals(0L, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countPostings().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countRg05PostingReconciliations().executeAsOne())
            assertIs<Rg05ExecutionResult.Accepted>(executor.execute(case.importOperations[1]))
            assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(3L, database.ledgerQueries.countPostings().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countRg05PostingReconciliations().executeAsOne())
            assertIs<Rg05ExecutionResult.ReceiptAccepted>(executor.execute(case.importOperations[2]))
            assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(3L, database.ledgerQueries.countPostings().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countRg05PostingReconciliations().executeAsOne())
            assertEquals(listOf("COMPLETE", "COMPLETE"), database.ledgerQueries.selectRg05RelationCompleteness("ledger-a", "association-group-rg05-imported").executeAsList())
        }
    }
}

private fun rg05RepositoryFile(relative: String): Path {
    var path = Path.of(System.getProperty("user.dir"))
    repeat(6) {
        if (Files.isRegularFile(path.resolve("settings.gradle.kts"))) return path.resolve(relative)
        path = path.parent ?: error("repository root not found")
    }
    error("repository root not found")
}
