package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.ConfirmImportCandidate
import com.unifiedledger.application.ExecuteImportIntake
import com.unifiedledger.application.ExecuteRg04ImportOperation
import com.unifiedledger.application.ImportCandidateConfirmRequest
import com.unifiedledger.application.ImportCandidateDecisionResult
import com.unifiedledger.application.ImportCandidateFormalFactory
import com.unifiedledger.application.ImportCandidateFormalizationInput
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportCommitIds
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportConfirmDecisionFields
import com.unifiedledger.application.ImportConfirmationId
import com.unifiedledger.application.ImportContentFingerprint
import com.unifiedledger.application.ImportEvidenceId
import com.unifiedledger.application.ImportFormalCommit
import com.unifiedledger.application.ImportFormalIds
import com.unifiedledger.application.ImportIdSource
import com.unifiedledger.application.ImportIntakeIdSource
import com.unifiedledger.application.ImportIntakeIds
import com.unifiedledger.application.ImportIntakeRequest
import com.unifiedledger.application.ImportIntakeResult
import com.unifiedledger.application.ImportRecordKind
import com.unifiedledger.application.ImportRequestId
import com.unifiedledger.application.ImportRequestIdentity
import com.unifiedledger.application.ImportResolvedSourceFacts
import com.unifiedledger.application.ImportSourceFacts
import com.unifiedledger.application.ImportSourceId
import com.unifiedledger.application.ImportStatusHistoryId
import com.unifiedledger.application.Rg04DecodedImportOperation
import com.unifiedledger.application.Rg04ImportExecutionResult
import com.unifiedledger.application.Rg04ImportReturnedId
import com.unifiedledger.application.Rg04ImportReturnedIdKind
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseCommand
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseIds
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.createAssetPaidOrdinaryExpense
import java.nio.file.Files
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * T-24/T-25: v20 -> v21 migration (fresh = migrated, silo rows preserved, guards in
 * place, DDL-failure atomic rollback) and the RG-04 silo / shared spine coexistence
 * in one database (D-092:1335).
 */
class ImportSpineMigrationCoexistenceTest {
    private val ledgerId = LedgerId("ledger-p402")
    private val cny = CurrencyUnit("CNY", 2)
    private val hashR1 = "sha256:afd8167ab6353423ef5632ae2a458f79bc4788f833f304c66b2fc8cf552a07e2"

    private fun migrationProperties(): Properties = Properties().apply {
        setProperty("foreign_keys", "true")
        setProperty("busy_timeout", "5000")
    }

    private fun queryCount(driver: JdbcSqliteDriver, sql: String): Long = driver.executeQuery(
        null,
        sql,
        { cursor ->
            cursor.next()
            app.cash.sqldelight.db.QueryResult.Value(cursor.getLong(0)!!)
        },
        0,
    ).value

    private fun spineCatalog(): LedgerCatalog = when (
        val result = LedgerCatalog.create(
            accounts = listOf(
                Account(AccountId("account-asset-a"), ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                Account(AccountId("expense-account-food"), ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false),
                Account(AccountId("income-account-salary"), ledgerId, AccountKind.INCOME, cny, ownedByUser = false, realAccount = false),
            ),
            categories = listOf(
                Category(CategoryId("category-primary-food"), ledgerId, parentId = null, postingAccountId = null, active = true, kind = CategoryKind.EXPENSE),
                Category(CategoryId("category-food"), ledgerId, parentId = CategoryId("category-primary-food"), postingAccountId = AccountId("expense-account-food"), active = true, kind = CategoryKind.EXPENSE),
                Category(CategoryId("category-primary-salary"), ledgerId, parentId = null, postingAccountId = null, active = true, kind = CategoryKind.INCOME),
                Category(CategoryId("category-salary"), ledgerId, parentId = CategoryId("category-primary-salary"), postingAccountId = AccountId("income-account-salary"), active = true, kind = CategoryKind.INCOME),
            ),
        )
    ) {
        is DomainResult.Success -> result.value
        is DomainResult.Failure -> error("spine test catalog failure: ${result.violation}")
    }

    private class FormalFactory(
        private val catalog: LedgerCatalog,
    ) : ImportCandidateFormalFactory {
        override fun create(
            input: ImportCandidateFormalizationInput,
            ids: ImportCommitIds,
        ): DomainResult<ImportFormalCommit> {
            val resolved = input.resolved
            val decisionFields = input.decisionFields as ImportConfirmDecisionFields.OrdinaryFlow
            val currency = CurrencyUnit(resolved.currencyCode, resolved.currencyPrecision)
            val money = Money.ofMinor(resolved.amountMinor, currency)
            val times = TransactionTimes.collapsed(Instant.parse(resolved.occurredAt))
            return when (
                val created = createAssetPaidOrdinaryExpense(
                    catalog,
                    AssetPaidOrdinaryExpenseCommand(input.ledgerId, money, decisionFields.categoryId, decisionFields.fundingAccountId, times),
                    AssetPaidOrdinaryExpenseIds(
                        transactionId = ids.formalIds.transactionId,
                        versionId = ids.formalIds.versionId,
                        postingSetId = ids.formalIds.postingSetId,
                        expensePostingId = ids.formalIds.postingIds[0],
                        paymentPostingId = ids.formalIds.postingIds[1],
                    ),
                )
            ) {
                is DomainResult.Success -> DomainResult.Success(ImportFormalCommit(ids.confirmationId, ids.statusHistoryId, created.value))
                is DomainResult.Failure -> DomainResult.Failure(created.violation)
            }
        }
    }

    @Test
    fun versionTwentyToTwentyOneCreatesEmptySpineOwnersAndPreservesRg04SiloRows() {
        val path = Files.createTempFile("spine-v20-v21-", ".db")
        val url = "jdbc:sqlite:${path.toAbsolutePath()}"
        try {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 20)
                driver.execute(
                    null,
                    "INSERT INTO rg04_import_request VALUES ('ledger-a', 'rg04-existing', 'IMPORT_SOURCE')",
                    0,
                )
            }
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, oldVersion = 20, newVersion = 21)
            }
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                assertEquals(23, LedgerDatabase.Schema.version)
                assertEquals(1L, database.ledgerQueries.countRg04ImportRequests().executeAsOne())
                assertEquals(0L, database.ledgerQueries.countImportRequests().executeAsOne())
                assertEquals(0L, database.ledgerQueries.countImportSourceRecords().executeAsOne())
                assertEquals(0L, database.ledgerQueries.countImportEvidence().executeAsOne())
                assertEquals(0L, database.ledgerQueries.countImportCandidates().executeAsOne())
                assertEquals(0L, database.ledgerQueries.countImportCandidateStatusHistory().executeAsOne())
                assertEquals(0L, database.ledgerQueries.countImportDecisionSnapshots().executeAsOne())
                assertEquals(0L, database.ledgerQueries.countImportConfirmations().executeAsOne())
                assertEquals(0L, database.ledgerQueries.countImportReceipts().executeAsOne())
                assertEquals("1", database.ledgerQueries.foreignKeysEnabled().executeAsOne())
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE type = 'trigger' AND name = 'import_status_history_sequence_guard'"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE type = 'trigger' AND name = 'import_status_history_transition_guard'"))
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE name = 'p402_migration_guard'"))
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE name = 'p402_silo_guard'"))
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM pragma_foreign_key_check"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun versionTwentyToTwentyOneDdlFailureRollsBackEverySpineOwner() {
        val path = Files.createTempFile("spine-v20-v21-rollback-", ".db")
        val url = "jdbc:sqlite:${path.toAbsolutePath()}"
        try {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 20)
                driver.execute(null, "CREATE TABLE import_request (blocker TEXT)", 0)
                assertFailsWith<SQLException> {
                    LedgerDatabase(driver).transaction {
                        LedgerDatabase.Schema.migrate(driver, oldVersion = 20, newVersion = 21)
                    }
                }
            }
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                // The blocker is the only import_* object; no spine table, trigger, or
                // temporary guard of the aborted migration survives.
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE name LIKE 'import\\_%' ESCAPE '\\'"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE name = 'import_request'"))
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE name = 'import_source_record'"))
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'import\\_%' ESCAPE '\\'"))
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE name = 'p402_migration_guard'"))
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE name = 'p402_silo_guard'"))
                // The v20 owners are untouched.
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE name = 'rg04_import_request'"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM ledger_transaction"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun rg04SiloAndSharedSpineCoexistInOneDatabaseWithoutCrossTalk() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val case = decodedCase()

            // Frozen RG-04 silo on ledger-a: intake + confirm + equivalent replay.
            val rg04Executor = ExecuteRg04ImportOperation(SqlDelightRg04ImportStore(database, driver, case.catalog))
            assertIs<Rg04ImportExecutionResult.Accepted>(rg04Executor.execute(case.importOperations[0]))
            assertIs<Rg04ImportExecutionResult.Accepted>(rg04Executor.execute(case.importOperations[2]))
            val rg04Replay = assertIs<Rg04ImportExecutionResult.NoChange>(rg04Executor.execute(case.importOperations[2]))
            assertEquals(
                listOf(
                    Rg04ImportReturnedId(
                        Rg04ImportReturnedIdKind.CONFIRMATION,
                        (case.importOperations[2] as Rg04DecodedImportOperation.Confirm).snapshot.confirmationId,
                    ),
                    Rg04ImportReturnedId(
                        Rg04ImportReturnedIdKind.TRANSACTION,
                        (case.importOperations[2] as Rg04DecodedImportOperation.Confirm).snapshot.formalIds.transactionId.value,
                    ),
                ),
                rg04Replay.returnedIds,
            )

            // Shared spine on ledger-p402: intake + confirm + replay.
            val intakeRequest = ImportIntakeRequest(
                identity = ImportRequestIdentity(ledgerId, ImportRequestId("req-a-intake")),
                inputRef = "batch-p402-a",
                recordOrdinal = 0,
                recordKind = ImportRecordKind.ORDINARY_FLOW_SOURCE,
                facts = ImportSourceFacts(12850, "CNY", 2, "2026-08-01T12:30:00+08:00", "out", "settled"),
                completeness = ImportCompleteness.VALID_COMPLETE,
            )
            val intakeIds = object : ImportIntakeIdSource {
                override fun next() = ImportIntakeIds(
                    ImportSourceId("source-a"), ImportEvidenceId("evidence-a"),
                    ImportCandidateId("candidate-a"), ImportStatusHistoryId("status-a-1"),
                )
            }
            val commitIds = object : ImportIdSource {
                override fun next() = ImportCommitIds(
                    ImportConfirmationId("confirmation-a"), ImportStatusHistoryId("status-a-2"),
                    ImportFormalIds(
                        TransactionId("tx-a"), TransactionVersionId("version-a-v1"), PostingSetId("posting-set-a"),
                        listOf(PostingId("posting-expense-a"), PostingId("posting-asset-a")),
                    ),
                )
            }
            val spineStore = SqlDelightImportSpineStore(database, driver)
            assertIs<ImportIntakeResult.Accepted>(
                ExecuteImportIntake(spineStore, intakeIds, ImportContentFingerprint()).execute(intakeRequest),
            )
            val confirmRequest = ImportCandidateConfirmRequest(
                identity = ImportRequestIdentity(ledgerId, ImportRequestId("req-a-confirm")),
                candidateId = ImportCandidateId("candidate-a"),
                expectedContentHash = hashR1,
                explicitConfirmedAt = "2026-08-07T10:00:00+08:00",
                decisionFields = ImportConfirmDecisionFields.OrdinaryFlow(
                    categoryId = CategoryId("category-food"),
                    fundingAccountId = AccountId("account-asset-a"),
                ),
            )
            val confirm = ConfirmImportCandidate(
                spineStore, commitIds,
                FormalFactory(spineCatalog()),
            )
            assertIs<ImportCandidateDecisionResult.Accepted>(confirm.execute(confirmRequest))
            assertIs<ImportCandidateDecisionResult.NoChange>(confirm.execute(confirmRequest))

            // Independent ownership: silo and spine rows never cross-reference. The
            // shared formal chain legitimately holds both ledgers' transactions.
            assertEquals(2L, database.ledgerQueries.countRg04ImportRequests().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countRg04ImportConfirmations().executeAsOne())
            assertEquals(2L, database.ledgerQueries.countImportRequests().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countImportConfirmations().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countImportSourceRecords().executeAsOne())
            assertEquals(2L, database.ledgerQueries.countTransactions().executeAsOne())
        } finally {
            driver.close()
        }
    }
}
