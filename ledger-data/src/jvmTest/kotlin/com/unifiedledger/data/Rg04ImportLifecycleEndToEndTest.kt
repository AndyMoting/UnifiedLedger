package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.*
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class Rg04ImportLifecycleEndToEndTest {
    @Test
    fun decoderExposesEightImportLifecycleOperationsWithoutV2Loading() {
        val raw = Files.readString(repositoryFile("golden/rules/rg-04.json"))
        val decoded = assertIs<Rg04RawJsonDecodeResult.Success>(decodeRg04RawJson(raw)).value
        assertEquals(8, decoded.importOperations.size)
        assertIs<Rg04DecodedImportOperation.Source>(decoded.importOperations[0])
        assertIs<Rg04DecodedImportOperation.Source>(decoded.importOperations[1])
        assertIs<Rg04DecodedImportOperation.Confirm>(decoded.importOperations[2])
        assertIs<Rg04DecodedImportOperation.Confirm>(decoded.importOperations[3])
        assertIs<Rg04DecodedImportOperation.Mirror>(decoded.importOperations[4])
        assertIs<Rg04DecodedImportOperation.Mirror>(decoded.importOperations[5])
        assertIs<Rg04DecodedImportOperation.Source>(decoded.importOperations[6])
        assertIs<Rg04DecodedImportOperation.Source>(decoded.importOperations[7])
    }

    @Test
    fun executesEightRawV1OperationsWithStableReplayAndNoInferredFormalState() {
        val case = decodedCase()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val executor = ExecuteRg04ImportOperation(SqlDelightRg04ImportStore(database, driver, case.catalog))
            val observed = case.importOperations.map(executor::execute)
            assertIs<Rg04ImportExecutionResult.Accepted>(observed[0])
            assertEquals((observed[0] as Rg04ImportExecutionResult.Accepted).returnedIds, assertIs<Rg04ImportExecutionResult.NoChange>(observed[1]).returnedIds)
            assertIs<Rg04ImportExecutionResult.Accepted>(observed[2])
            assertEquals((observed[2] as Rg04ImportExecutionResult.Accepted).returnedIds, assertIs<Rg04ImportExecutionResult.NoChange>(observed[3]).returnedIds)
            val beforeMirror = formalCounts(database)
            assertIs<Rg04ImportExecutionResult.Accepted>(observed[4])
            assertEquals((observed[4] as Rg04ImportExecutionResult.Accepted).returnedIds, assertIs<Rg04ImportExecutionResult.NoChange>(observed[5]).returnedIds)
            assertEquals(beforeMirror, formalCounts(database))
            assertIs<Rg04ImportExecutionResult.Accepted>(observed[6])
            assertEquals((observed[6] as Rg04ImportExecutionResult.Accepted).returnedIds, assertIs<Rg04ImportExecutionResult.NoChange>(observed[7]).returnedIds)

            assertEquals(4L, database.ledgerQueries.countRg04ImportRequests().executeAsOne())
            assertEquals(3L, database.ledgerQueries.countRg04ImportSources().executeAsOne())
            assertEquals(3L, database.ledgerQueries.countRg04ImportEvidence().executeAsOne())
            assertEquals(2L, database.ledgerQueries.countRg04ImportCandidates().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countRg04ImportConfirmations().executeAsOne())
            assertEquals(2L, database.ledgerQueries.countRg04ImportMatches().executeAsOne())
            assertEquals(4L, database.ledgerQueries.countRg04ImportReceipts().executeAsOne())
            assertEquals(listOf(1L, 1L, 3L, 1L, 3L), formalCounts(database))
            assertEquals(
                listOf(
                    listOf("match-rg04-asset-imported", "evidence-rg04-asset-debit", "posting-asset-rg04-imported", "ASSET_SOURCE"),
                    listOf("match-rg04-liability-mirror", "evidence-rg04-liability-mirror", "posting-liability-rg04-imported", "LIABILITY_MIRROR"),
                ),
                database.ledgerQueries.selectRg04ImportEvidenceMatches().executeAsList().map {
                    listOf(it.match_id, it.evidence_id, it.posting_id, it.match_kind)
                },
            )
            val confirmed = (case.importOperations[2] as Rg04DecodedImportOperation.Confirm).snapshot
            assertEquals(
                setOf(
                    confirmed.assetReconciliationId to "posting-asset-rg04-imported",
                    confirmed.liabilityReconciliationId to "posting-liability-rg04-imported",
                ),
                database.ledgerQueries.selectRg04PostingReconciliations().executeAsList()
                    .filter { it.posting_id.endsWith("rg04-imported") }
                    .map { it.reconciliation_id to it.posting_id }
                    .toSet(),
            )

            val missing = (case.importOperations[6] as Rg04DecodedImportOperation.Source).snapshot
            assertEquals(70_00L, missing.funding[0].amount.minorUnits)
            assertEquals(50_00L, missing.funding[1].amount.minorUnits)
            assertEquals(null, missing.funding[1].accountId)
            assertEquals("0.58", missing.confidence)

            val beforeReject = counts(database)
            val secondConfirmation = executor.execute(
                Rg04DecodedImportOperation.Confirm(
                    confirmed.copy(requestId = RequestId("request-second-confirmation")),
                    Rg04Expected.Accepted,
                ),
            )
            assertEquals(
                Rg04ImportExecutionResult.Rejected(Rg04ImportExecutionError.CANDIDATE_NOT_PENDING),
                secondConfirmation,
            )
            assertEquals(beforeReject, counts(database))

            val mirror = (case.importOperations[4] as Rg04DecodedImportOperation.Mirror).snapshot
            val mismatch = executor.execute(
                Rg04DecodedImportOperation.Mirror(
                    mirror.copy(
                        requestId = RequestId("request-mismatch"),
                        sourceId = Rg04SourceId("source-mismatch"),
                        evidenceId = Rg04EvidenceId("evidence-mismatch"),
                        evidenceLinkId = "match-mismatch",
                        accountId = AccountId("asset-bank-a"),
                    ),
                    Rg04Expected.Accepted,
                ),
            )
            assertEquals(Rg04ImportExecutionResult.Rejected(Rg04ImportExecutionError.MIRROR_TARGET_MISMATCH), mismatch)
            assertEquals(beforeReject, counts(database))
        } finally {
            driver.close()
        }
    }

    @Test
    fun injectedLifecycleFailuresRollBackEveryOwner() {
        val case = decodedCase()
        Rg04ImportFailurePoint.entries.forEach { failurePoint ->
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            try {
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val normal = ExecuteRg04ImportOperation(SqlDelightRg04ImportStore(database, driver, case.catalog))
                when (failurePoint) {
                    Rg04ImportFailurePoint.SOURCE_AFTER_CANDIDATE -> Unit
                    Rg04ImportFailurePoint.CONFIRMATION_AFTER_FORMAL -> normal.execute(case.importOperations[0])
                    Rg04ImportFailurePoint.MIRROR_AFTER_MATCH -> {
                        normal.execute(case.importOperations[0])
                        normal.execute(case.importOperations[2])
                    }
                }
                val before = counts(database)
                val failing = ExecuteRg04ImportOperation(
                    SqlDelightRg04ImportStore(database, driver, case.catalog) {
                        if (it == failurePoint) error("injected")
                    },
                )
                val operation = when (failurePoint) {
                    Rg04ImportFailurePoint.SOURCE_AFTER_CANDIDATE -> case.importOperations[0]
                    Rg04ImportFailurePoint.CONFIRMATION_AFTER_FORMAL -> case.importOperations[2]
                    Rg04ImportFailurePoint.MIRROR_AFTER_MATCH -> case.importOperations[4]
                }
                assertFailsWith<IllegalStateException> { failing.execute(operation) }
                assertEquals(before, counts(database), failurePoint.name)
            } finally {
                driver.close()
            }
        }
    }

    @Test
    fun mirrorTargetSurvivesStoreRestartAndAcceptedOwnersRejectDirectMutation() {
        val case = decodedCase()
        val path = Files.createTempFile("rg04-import-restart-", ".db")
        val url = "jdbc:sqlite:${path.toAbsolutePath()}"
        try {
            JdbcSqliteDriver(url).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val executor = ExecuteRg04ImportOperation(SqlDelightRg04ImportStore(LedgerDatabase(driver), driver, case.catalog))
                assertIs<Rg04ImportExecutionResult.Accepted>(executor.execute(case.importOperations[0]))
                assertIs<Rg04ImportExecutionResult.Accepted>(executor.execute(case.importOperations[2]))
            }
            JdbcSqliteDriver(url).use { driver ->
                val database = LedgerDatabase(driver)
                val executor = ExecuteRg04ImportOperation(SqlDelightRg04ImportStore(database, driver, case.catalog))
                assertIs<Rg04ImportExecutionResult.Accepted>(executor.execute(case.importOperations[4]))
                assertEquals(2L, database.ledgerQueries.countRg04ImportMatches().executeAsOne())
                assertFailsWith<SQLException> {
                    driver.execute(null, "UPDATE rg04_import_source SET source_kind = 'changed'", 0)
                }
                assertFailsWith<SQLException> {
                    driver.execute(null, "DELETE FROM rg04_import_request WHERE request_id = 'request-rg04-import-complete'", 0)
                }
                assertEquals(3L, database.ledgerQueries.countRg04ImportReceipts().executeAsOne())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun concurrentIdenticalSourceRequestCommitsOnceAndReplaysOnce() {
        val case = decodedCase()
        val path = Files.createTempFile("rg04-import-concurrent-", ".db")
        val url = "jdbc:sqlite:${path.toAbsolutePath()}"
        try {
            JdbcSqliteDriver(url).use(LedgerDatabase.Schema::create)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val futures = List(2) {
                    pool.submit<Rg04ImportExecutionResult> {
                        JdbcSqliteDriver(url).use { driver ->
                            ExecuteRg04ImportOperation(
                                SqlDelightRg04ImportStore(LedgerDatabase(driver), driver, case.catalog),
                            ).execute(case.importOperations[0])
                        }
                    }
                }
                val results = futures.map { it.get() }
                assertEquals(1, results.count { it is Rg04ImportExecutionResult.Accepted })
                assertEquals(1, results.count { it is Rg04ImportExecutionResult.NoChange })
            } finally {
                pool.shutdownNow()
            }
            JdbcSqliteDriver(url).use { driver ->
                val database = LedgerDatabase(driver)
                assertEquals(1L, database.ledgerQueries.countRg04ImportRequests().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg04ImportCandidates().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg04ImportReceipts().executeAsOne())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun concurrentConflictingSourceRequestCommitsOneWinnerWithoutPartialLoser() {
        val case = decodedCase()
        val original = case.importOperations[0] as Rg04DecodedImportOperation.Source
        val changed = original.copy(snapshot = original.snapshot.copy(
            observedAt = kotlin.time.Instant.parse("2026-02-11T12:00:01+08:00"),
            observedAtText = "2026-02-11T12:00:01+08:00",
        ))
        val path = Files.createTempFile("rg04-import-conflict-concurrent-", ".db")
        val url = "jdbc:sqlite:${path.toAbsolutePath()}"
        try {
            JdbcSqliteDriver(url).use(LedgerDatabase.Schema::create)
            val results = concurrentExecute(url, case, listOf(original, changed))
            assertEquals(1, results.count { it is Rg04ImportExecutionResult.Accepted })
            assertEquals(1, results.count { it is Rg04ImportExecutionResult.RequestIdentityConflict })
            JdbcSqliteDriver(url).use { driver ->
                val database = LedgerDatabase(driver)
                assertEquals(1L, database.ledgerQueries.countRg04ImportRequests().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg04ImportSources().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg04ImportEvidence().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg04ImportCandidates().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg04ImportReceipts().executeAsOne())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun concurrentEquivalentConfirmationCommitsOnceAndReplaysOnce() {
        val case = decodedCase()
        val path = Files.createTempFile("rg04-import-confirm-concurrent-", ".db")
        val url = "jdbc:sqlite:${path.toAbsolutePath()}"
        try {
            JdbcSqliteDriver(url).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                ExecuteRg04ImportOperation(SqlDelightRg04ImportStore(database, driver, case.catalog)).execute(case.importOperations[0])
            }
            val pool = Executors.newFixedThreadPool(2)
            try {
                val futures = List(2) {
                    pool.submit<Rg04ImportExecutionResult> {
                        JdbcSqliteDriver(url).use { driver ->
                            ExecuteRg04ImportOperation(
                                SqlDelightRg04ImportStore(LedgerDatabase(driver), driver, case.catalog),
                            ).execute(case.importOperations[2])
                        }
                    }
                }
                val results = futures.map { it.get() }
                assertEquals(1, results.count { it is Rg04ImportExecutionResult.Accepted })
                assertEquals(1, results.count { it is Rg04ImportExecutionResult.NoChange })
            } finally {
                pool.shutdownNow()
            }
            JdbcSqliteDriver(url).use { driver ->
                val database = LedgerDatabase(driver)
                assertEquals(2L, database.ledgerQueries.countRg04ImportRequests().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg04ImportConfirmations().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg04ImportMatches().executeAsOne())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun concurrentDistinctConfirmationsAcceptOnceAndRollBackLosingClaim() {
        val case = decodedCase()
        val original = case.importOperations[2] as Rg04DecodedImportOperation.Confirm
        val distinct = original.copy(snapshot = original.snapshot.copy(requestId = RequestId("request-rg04-confirm-concurrent-distinct")))
        val path = Files.createTempFile("rg04-import-confirm-owner-concurrent-", ".db")
        val url = "jdbc:sqlite:${path.toAbsolutePath()}"
        try {
            JdbcSqliteDriver(url).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                ExecuteRg04ImportOperation(SqlDelightRg04ImportStore(database, driver, case.catalog)).execute(case.importOperations[0])
            }
            val results = concurrentExecute(url, case, listOf(original, distinct))
            assertEquals(1, results.count { it is Rg04ImportExecutionResult.Accepted })
            assertEquals(
                1,
                results.count { it == Rg04ImportExecutionResult.Rejected(Rg04ImportExecutionError.CANDIDATE_NOT_PENDING) },
            )
            JdbcSqliteDriver(url).use { driver ->
                val database = LedgerDatabase(driver)
                assertEquals(2L, database.ledgerQueries.countRg04ImportRequests().executeAsOne())
                assertEquals(2L, database.ledgerQueries.countRg04ImportReceipts().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg04ImportConfirmations().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())
                assertEquals(3L, database.ledgerQueries.countPostings().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg04ImportMatches().executeAsOne())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun concurrentEquivalentMirrorCommitsOnceAndReplaysOnce() {
        val case = decodedCase()
        val path = Files.createTempFile("rg04-import-mirror-concurrent-", ".db")
        val url = "jdbc:sqlite:${path.toAbsolutePath()}"
        try {
            JdbcSqliteDriver(url).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val executor = ExecuteRg04ImportOperation(SqlDelightRg04ImportStore(database, driver, case.catalog))
                executor.execute(case.importOperations[0])
                executor.execute(case.importOperations[2])
            }
            val pool = Executors.newFixedThreadPool(2)
            try {
                val futures = List(2) {
                    pool.submit<Rg04ImportExecutionResult> {
                        JdbcSqliteDriver(url).use { driver ->
                            ExecuteRg04ImportOperation(
                                SqlDelightRg04ImportStore(LedgerDatabase(driver), driver, case.catalog),
                            ).execute(case.importOperations[4])
                        }
                    }
                }
                val results = futures.map { it.get() }
                assertEquals(1, results.count { it is Rg04ImportExecutionResult.Accepted })
                assertEquals(1, results.count { it is Rg04ImportExecutionResult.NoChange })
            } finally {
                pool.shutdownNow()
            }
            JdbcSqliteDriver(url).use { driver ->
                val database = LedgerDatabase(driver)
                assertEquals(3L, database.ledgerQueries.countRg04ImportRequests().executeAsOne())
                assertEquals(2L, database.ledgerQueries.countRg04ImportMatches().executeAsOne())
                assertEquals(2L, database.ledgerQueries.countRg04ImportSources().executeAsOne())
                assertEquals(2L, database.ledgerQueries.countRg04ImportEvidence().executeAsOne())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun concurrentDistinctMirrorsAcceptOnceAndRollBackLosingEvidence() {
        val case = decodedCase()
        val original = case.importOperations[4] as Rg04DecodedImportOperation.Mirror
        val distinct = original.copy(snapshot = original.snapshot.copy(
            requestId = RequestId("request-rg04-mirror-concurrent-distinct"),
            sourceId = Rg04SourceId("source-rg04-mirror-concurrent-distinct"),
            evidenceId = Rg04EvidenceId("evidence-rg04-mirror-concurrent-distinct"),
            evidenceLinkId = "match-rg04-mirror-concurrent-distinct",
        ))
        val path = Files.createTempFile("rg04-import-mirror-owner-concurrent-", ".db")
        val url = "jdbc:sqlite:${path.toAbsolutePath()}"
        try {
            JdbcSqliteDriver(url).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val executor = ExecuteRg04ImportOperation(SqlDelightRg04ImportStore(database, driver, case.catalog))
                executor.execute(case.importOperations[0])
                executor.execute(case.importOperations[2])
            }
            val results = concurrentExecute(url, case, listOf(original, distinct))
            assertEquals(1, results.count { it is Rg04ImportExecutionResult.Accepted })
            assertEquals(
                1,
                results.count { it == Rg04ImportExecutionResult.Rejected(Rg04ImportExecutionError.RECONCILIATION_PRECONDITION_FAILED) },
            )
            JdbcSqliteDriver(url).use { driver ->
                val database = LedgerDatabase(driver)
                assertEquals(3L, database.ledgerQueries.countRg04ImportRequests().executeAsOne())
                assertEquals(3L, database.ledgerQueries.countRg04ImportReceipts().executeAsOne())
                assertEquals(2L, database.ledgerQueries.countRg04ImportSources().executeAsOne())
                assertEquals(2L, database.ledgerQueries.countRg04ImportEvidence().executeAsOne())
                assertEquals(2L, database.ledgerQueries.countRg04ImportMatches().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())
                assertEquals(3L, database.ledgerQueries.countPostings().executeAsOne())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun conflictingSourceAndDistinctConfirmationOrMirrorAreAtomicTypedOutcomes() {
        val case = decodedCase()
        val source = (case.importOperations[0] as Rg04DecodedImportOperation.Source).snapshot
        val changedSource = source.copy(
            observedAt = kotlin.time.Instant.parse("2026-02-11T12:00:01+08:00"),
            observedAtText = "2026-02-11T12:00:01+08:00",
        )
        val sourceDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(sourceDriver)
            val sourceDatabase = LedgerDatabase(sourceDriver)
            val sourceStore = SqlDelightRg04ImportStore(sourceDatabase, sourceDriver, case.catalog)
            val sourceExecutor = ExecuteRg04ImportOperation(sourceStore)
            val sourceResults = listOf(source, changedSource).map {
                sourceExecutor.execute(Rg04DecodedImportOperation.Source(it, Rg04Expected.Accepted))
            }
            assertEquals(1, sourceResults.count { it is Rg04ImportExecutionResult.Accepted })
            assertEquals(1, sourceResults.count { it is Rg04ImportExecutionResult.RequestIdentityConflict })
            assertEquals(1L, sourceDatabase.ledgerQueries.countRg04ImportRequests().executeAsOne())
        } finally {
            sourceDriver.close()
        }

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val executor = ExecuteRg04ImportOperation(SqlDelightRg04ImportStore(database, driver, case.catalog))
            executor.execute(case.importOperations[0])
            val confirm = case.importOperations[2] as Rg04DecodedImportOperation.Confirm
            val distinctConfirm = confirm.copy(snapshot = confirm.snapshot.copy(requestId = RequestId("request-rg04-confirm-distinct")))
            val first = executor.execute(confirm)
            val second = executor.execute(distinctConfirm)
            assertIs<Rg04ImportExecutionResult.Accepted>(first)
            assertEquals(Rg04ImportExecutionResult.Rejected(Rg04ImportExecutionError.CANDIDATE_NOT_PENDING), second)
            assertEquals(1L, database.ledgerQueries.countRg04ImportConfirmations().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())

            val mirror = case.importOperations[4] as Rg04DecodedImportOperation.Mirror
            val distinctMirror = mirror.copy(snapshot = mirror.snapshot.copy(
                requestId = RequestId("request-rg04-mirror-distinct"),
                sourceId = Rg04SourceId("source-rg04-mirror-distinct"),
                evidenceId = Rg04EvidenceId("evidence-rg04-mirror-distinct"),
                evidenceLinkId = "match-rg04-mirror-distinct",
            ))
            val beforeMirror = counts(database)
            assertIs<Rg04ImportExecutionResult.Accepted>(executor.execute(mirror))
            val afterMirror = counts(database)
            assertEquals(beforeMirror.take(5), afterMirror.take(5))
            assertEquals(
                Rg04ImportExecutionResult.Rejected(Rg04ImportExecutionError.RECONCILIATION_PRECONDITION_FAILED),
                executor.execute(distinctMirror),
            )
            assertEquals(afterMirror, counts(database))
        } finally {
            driver.close()
        }
    }

    @Test
    fun importSnapshotsAndComponentsAreAppendOnly() {
        val case = decodedCase()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val executor = ExecuteRg04ImportOperation(SqlDelightRg04ImportStore(database, driver, case.catalog))
            executor.execute(case.importOperations[0])
            executor.execute(case.importOperations[2])
            executor.execute(case.importOperations[4])
            listOf(
                "UPDATE rg04_import_source_snapshot SET total_minor = total_minor",
                "DELETE FROM rg04_import_source_snapshot",
                "UPDATE rg04_import_source_component_snapshot SET amount_minor = amount_minor",
                "DELETE FROM rg04_import_source_component_snapshot",
                "UPDATE rg04_import_confirmation_snapshot SET category_id = category_id",
                "DELETE FROM rg04_import_confirmation_snapshot",
                "UPDATE rg04_import_confirmation_component_snapshot SET amount_minor = amount_minor",
                "DELETE FROM rg04_import_confirmation_component_snapshot",
                "UPDATE rg04_import_mirror_snapshot SET amount_minor = amount_minor",
                "DELETE FROM rg04_import_mirror_snapshot",
            ).forEach { statement ->
                assertFailsWith<SQLException> { driver.execute(null, statement, 0) }
            }
        } finally {
            driver.close()
        }
    }

    @Test
    fun importedFormalAggregateAndReplayAreProtectedFromDirectMutation() {
        val case = decodedCase()
        val source = case.importOperations[0]
        val confirmation = case.importOperations[2] as Rg04DecodedImportOperation.Confirm
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val executor = ExecuteRg04ImportOperation(SqlDelightRg04ImportStore(database, driver, case.catalog))
            assertIs<Rg04ImportExecutionResult.Accepted>(executor.execute(source))
            assertIs<Rg04ImportExecutionResult.Accepted>(executor.execute(confirmation))

            val ids = confirmation.snapshot.formalIds
            val relation = confirmation.snapshot.relationId
            val postingIds = ids.fundingPostingIds + ids.expensePostingId
            val mutations = buildList {
                add("UPDATE ledger_transaction SET kind = 'CREDIT_REPAYMENT' WHERE transaction_id = '${ids.transactionId.value}'")
                add("DELETE FROM ledger_transaction WHERE transaction_id = '${ids.transactionId.value}'")
                add("UPDATE ledger_transaction_current_version SET current_version_id = 'corrupt-current-version' WHERE transaction_id = '${ids.transactionId.value}'")
                add("DELETE FROM ledger_transaction_current_version WHERE transaction_id = '${ids.transactionId.value}'")
                add("UPDATE transaction_version SET version_number = 2 WHERE transaction_id = '${ids.transactionId.value}'")
                add("DELETE FROM transaction_version WHERE transaction_id = '${ids.transactionId.value}'")
                add("UPDATE posting_set SET posting_set_id = 'corrupt-posting-set' WHERE posting_set_id = '${ids.postingSetId.value}'")
                add("DELETE FROM posting_set WHERE posting_set_id = '${ids.postingSetId.value}'")
                postingIds.forEach { postingId ->
                    add("UPDATE posting SET amount_minor = amount_minor + 1 WHERE posting_id = '${postingId.value}'")
                    add("DELETE FROM posting WHERE posting_id = '${postingId.value}'")
                }
                add("UPDATE formal_relation SET relation_id = 'corrupt-relation' WHERE relation_id = '$relation'")
                add("DELETE FROM formal_relation WHERE relation_id = '$relation'")
                add("UPDATE formal_relation_member SET member_index = member_index + 1 WHERE relation_id = '$relation' AND member_index = 0")
                add("DELETE FROM formal_relation_member WHERE relation_id = '$relation' AND member_index = 0")
                add("UPDATE rg04_mixed_composition SET relation_id = 'corrupt-composition' WHERE relation_id = '$relation'")
                add("DELETE FROM rg04_mixed_composition WHERE relation_id = '$relation'")
                add("UPDATE rg04_mixed_composition_component SET amount_minor = amount_minor + 1 WHERE relation_id = '$relation'")
                add("DELETE FROM rg04_mixed_composition_component WHERE relation_id = '$relation' AND component_index = 0")
                postingIds.forEach { postingId ->
                    add("UPDATE rg04_posting_semantic SET category_id = 'corrupt-category' WHERE posting_id = '${postingId.value}'")
                    add("DELETE FROM rg04_posting_semantic WHERE posting_id = '${postingId.value}'")
                }
                listOf(confirmation.snapshot.assetReconciliationId, confirmation.snapshot.liabilityReconciliationId).forEach { reconciliationId ->
                    add("UPDATE rg04_posting_reconciliation SET status = 'MATCHED' WHERE reconciliation_id = '$reconciliationId'")
                    add("DELETE FROM rg04_posting_reconciliation WHERE reconciliation_id = '$reconciliationId'")
                }
            }
            mutations.forEach { statement ->
                assertFailsWith<SQLException>(statement) { driver.execute(null, statement, 0) }
            }
            assertEquals(
                Rg04ImportExecutionResult.NoChange(listOf(
                    Rg04ImportReturnedId(Rg04ImportReturnedIdKind.CONFIRMATION, confirmation.snapshot.confirmationId),
                    Rg04ImportReturnedId(Rg04ImportReturnedIdKind.TRANSACTION, confirmation.snapshot.formalIds.transactionId.value),
                )),
                executor.execute(confirmation),
            )
        }
    }

    @Test
    fun reconciliationTransitionsArePostingScopedAndCannotBeRewritten() {
        val case = decodedCase()
        val confirmation = case.importOperations[2] as Rg04DecodedImportOperation.Confirm
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val executor = ExecuteRg04ImportOperation(SqlDelightRg04ImportStore(database, driver, case.catalog))
            assertIs<Rg04ImportExecutionResult.Accepted>(executor.execute(case.importOperations[0]))
            assertIs<Rg04ImportExecutionResult.Accepted>(executor.execute(confirmation))

            val requestId = confirmation.snapshot.requestId.value
            val assetReconciliationId = confirmation.snapshot.assetReconciliationId
            val liabilityReconciliationId = confirmation.snapshot.liabilityReconciliationId
            val assetPostingId = confirmation.snapshot.formalIds.fundingPostingIds[0].value
            val liabilityPostingId = confirmation.snapshot.formalIds.fundingPostingIds[1].value
            val mirrorRequestId = "request-mirror-transition"
            driver.execute(
                null,
                "INSERT INTO rg04_import_request VALUES ('ledger-a', '$mirrorRequestId', 'MERGE_MIRROR')",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO rg04_import_mirror_snapshot VALUES ('ledger-a', '$mirrorRequestId', 'source-mirror-transition', 'evidence-mirror-transition', '2026-02-10T12:00:00+08:00', 'liability-account', 5000, 'CNY', 2, 'match-mirror-transition')",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO rg04_import_source VALUES ('ledger-a', 'source-mirror-transition', '$mirrorRequestId', 'evidence-mirror-transition', '2026-02-10T12:00:00+08:00', 'LIABILITY_MIRROR')",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO rg04_import_evidence VALUES ('ledger-a', 'evidence-mirror-transition', 'source-mirror-transition', 'LIABILITY_MIRROR', '2026-02-10T12:00:00+08:00')",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO rg04_import_evidence_match VALUES ('ledger-a', 'match-mirror-transition', 'evidence-mirror-transition', '$liabilityPostingId', 'LIABILITY_MIRROR')",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO rg04_import_request VALUES ('ledger-a', 'request-other-confirmation', 'CONFIRM_CANDIDATE')",
                0,
            )
            assertFailsWith<SQLException> {
                driver.execute(
                    null,
                    "INSERT INTO rg04_reconciliation_transition VALUES ('ledger-a', 'request-other-confirmation', '$assetReconciliationId', '$assetPostingId')",
                    0,
                )
            }
            assertFailsWith<SQLException> {
                driver.execute(
                    null,
                    "INSERT INTO rg04_reconciliation_transition VALUES ('ledger-a', '$requestId', '$liabilityReconciliationId', '$assetPostingId')",
                    0,
                )
            }

            driver.execute(
                null,
                "INSERT INTO rg04_reconciliation_transition VALUES ('ledger-a', '$mirrorRequestId', '$liabilityReconciliationId', '$liabilityPostingId')",
                0,
            )
            assertFailsWith<SQLException> {
                driver.execute(
                    null,
                    "UPDATE rg04_reconciliation_transition SET posting_id = '$assetPostingId' WHERE ledger_id = 'ledger-a' AND request_id = '$mirrorRequestId' AND reconciliation_id = '$liabilityReconciliationId'",
                    0,
                )
            }
            assertFailsWith<SQLException> {
                driver.execute(
                    null,
                    "UPDATE rg04_posting_reconciliation SET status = 'MATCHED' WHERE ledger_id = 'ledger-a' AND reconciliation_id = '$assetReconciliationId'",
                    0,
                )
            }
            assertFailsWith<SQLException> {
                driver.execute(
                    null,
                    "UPDATE rg04_posting_reconciliation SET posting_id = '$assetPostingId' WHERE ledger_id = 'ledger-a' AND reconciliation_id = '$liabilityReconciliationId'",
                    0,
                )
            }
            assertFailsWith<SQLException> {
                driver.execute(
                    null,
                    "DELETE FROM rg04_reconciliation_transition WHERE ledger_id = 'ledger-a' AND request_id = '$mirrorRequestId' AND reconciliation_id = '$liabilityReconciliationId'",
                    0,
                )
            }
            driver.execute(
                null,
                "UPDATE rg04_posting_reconciliation SET status = 'MATCHED' WHERE ledger_id = 'ledger-a' AND reconciliation_id = '$liabilityReconciliationId'",
                0,
            )
            driver.execute(
                null,
                "DELETE FROM rg04_reconciliation_transition WHERE ledger_id = 'ledger-a' AND request_id = '$mirrorRequestId' AND reconciliation_id = '$liabilityReconciliationId'",
                0,
            )
        }
    }

    @Test
    fun sameConfirmationRequestRequiresCompleteSnapshotAndOwnerEquivalence() {
        val case = decodedCase()
        val confirmation = case.importOperations[2] as Rg04DecodedImportOperation.Confirm
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val executor = ExecuteRg04ImportOperation(SqlDelightRg04ImportStore(database, driver, case.catalog))
            assertIs<Rg04ImportExecutionResult.Accepted>(executor.execute(case.importOperations[0]))
            assertIs<Rg04ImportExecutionResult.Accepted>(executor.execute(confirmation))

            val snapshot = confirmation.snapshot
            val mismatches = listOf(
                "version" to snapshot.copy(formalIds = snapshot.formalIds.copy(versionId = TransactionVersionId("version-conflict"))),
                "posting-set" to snapshot.copy(formalIds = snapshot.formalIds.copy(postingSetId = PostingSetId("posting-set-conflict"))),
                "expense-posting" to snapshot.copy(formalIds = snapshot.formalIds.copy(expensePostingId = PostingId("posting-expense-conflict"))),
                "asset-posting" to snapshot.copy(formalIds = snapshot.formalIds.copy(
                    fundingPostingIds = listOf(PostingId("posting-asset-conflict"), snapshot.formalIds.fundingPostingIds[1]),
                )),
                "liability-posting" to snapshot.copy(formalIds = snapshot.formalIds.copy(
                    fundingPostingIds = listOf(snapshot.formalIds.fundingPostingIds[0], PostingId("posting-liability-conflict")),
                )),
                "confirmed-status" to snapshot.copy(confirmedStatusId = "status-conflict"),
                "relation-display" to snapshot.copy(relationDisplayName = "conflicting display"),
                "asset-evidence-link" to snapshot.copy(assetEvidenceLinkId = "evidence-link-conflict"),
                "asset-reconciliation" to snapshot.copy(assetReconciliationId = "reconciliation-asset-conflict"),
                "liability-reconciliation" to snapshot.copy(liabilityReconciliationId = "reconciliation-liability-conflict"),
            )
            mismatches.forEach { (label, mutated) ->
                assertEquals(
                    Rg04ImportExecutionResult.RequestIdentityConflict,
                    executor.execute(confirmation.copy(snapshot = mutated)),
                    label,
                )
            }
            assertIs<Rg04ImportExecutionResult.NoChange>(executor.execute(confirmation))
        }
    }

    @Test
    fun typedLifecycleRejectionsAreAtomic() {
        val case = decodedCase()
        val confirm = case.importOperations[2] as Rg04DecodedImportOperation.Confirm
        val mirror = case.importOperations[4] as Rg04DecodedImportOperation.Mirror
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val store = SqlDelightRg04ImportStore(database, driver, case.catalog)
            val executor = ExecuteRg04ImportOperation(store)
            assertEquals(
                Rg04ImportExecutionResult.Rejected(Rg04ImportExecutionError.EXPLICIT_CONFIRMATION_REQUIRED, "confirmed"),
                store.commit(Rg04PreparedImportOperation.ConfirmCandidate(confirm.snapshot.copy(confirmed = false))),
            )
            assertEquals(List(12) { 0L }, counts(database))
            assertEquals(
                Rg04ImportExecutionResult.Rejected(Rg04ImportExecutionError.EXPLICIT_CONFIRMATION_REQUIRED, "confirmed"),
                executor.execute(confirm.copy(snapshot = confirm.snapshot.copy(confirmed = false))),
            )
            assertEquals(
                Rg04ImportExecutionResult.Rejected(Rg04ImportExecutionError.CANDIDATE_NOT_FOUND),
                executor.execute(confirm.copy(snapshot = confirm.snapshot.copy(
                    requestId = RequestId("request-unknown-candidate"),
                    candidateId = Rg04CandidateId("candidate-unknown"),
                ))),
            )
            assertEquals(
                Rg04ImportExecutionResult.Rejected(Rg04ImportExecutionError.MIRROR_TARGET_NOT_FOUND),
                executor.execute(mirror),
            )
            assertEquals(List(12) { 0L }, counts(database))
        }

        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val executor = ExecuteRg04ImportOperation(SqlDelightRg04ImportStore(database, driver, case.catalog))
            assertIs<Rg04ImportExecutionResult.Accepted>(executor.execute(case.importOperations[6]))
            val before = counts(database)
            assertEquals(
                Rg04ImportExecutionResult.Rejected(Rg04ImportExecutionError.CANDIDATE_INCOMPLETE),
                executor.execute(confirm.copy(snapshot = confirm.snapshot.copy(
                    requestId = RequestId("request-confirm-incomplete"),
                    candidateId = Rg04CandidateId("candidate-purchase-rg04-missing-leg"),
                ))),
            )
            assertEquals(before, counts(database))
        }

        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val executor = ExecuteRg04ImportOperation(SqlDelightRg04ImportStore(database, driver, case.catalog))
            val source = case.importOperations[0] as Rg04DecodedImportOperation.Source
            val invalidCategorySource = source.copy(snapshot = source.snapshot.copy(
                requestId = RequestId("request-domain-invalid-source"),
                sourceId = Rg04SourceId("source-domain-invalid"),
                evidenceId = Rg04EvidenceId("evidence-domain-invalid"),
                suggestedCategoryId = CategoryId("category-unknown"),
                candidateId = Rg04CandidateId("candidate-domain-invalid"),
                candidateStatusId = "status-domain-invalid-pending",
            ))
            assertIs<Rg04ImportExecutionResult.Accepted>(executor.execute(invalidCategorySource))
            val before = counts(database)
            assertEquals(
                Rg04ImportExecutionResult.Rejected(Rg04ImportExecutionError.DOMAIN_VALIDATION_FAILED),
                executor.execute(confirm.copy(snapshot = confirm.snapshot.copy(
                    requestId = RequestId("request-domain-invalid-confirm"),
                    candidateId = Rg04CandidateId("candidate-domain-invalid"),
                    categoryId = CategoryId("category-unknown"),
                ))),
            )
            assertEquals(before, counts(database))
        }

        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val executor = ExecuteRg04ImportOperation(SqlDelightRg04ImportStore(database, driver, case.catalog))
            assertIs<Rg04ImportExecutionResult.Accepted>(executor.execute(case.importOperations[0]))
            assertIs<Rg04ImportExecutionResult.Accepted>(executor.execute(confirm))

            val source = case.importOperations[0] as Rg04DecodedImportOperation.Source
            val secondSource = source.copy(snapshot = source.snapshot.copy(
                requestId = RequestId("request-ambiguous-source"),
                sourceId = Rg04SourceId("source-ambiguous"),
                evidenceId = Rg04EvidenceId("evidence-ambiguous"),
                candidateId = Rg04CandidateId("candidate-ambiguous"),
                candidateStatusId = "status-ambiguous-pending",
            ))
            assertIs<Rg04ImportExecutionResult.Accepted>(executor.execute(secondSource))
            val secondConfirm = confirm.copy(snapshot = confirm.snapshot.copy(
                requestId = RequestId("request-ambiguous-confirm"),
                candidateId = Rg04CandidateId("candidate-ambiguous"),
                formalIds = MixedPaymentExpenseIds(
                    TransactionId("tx-ambiguous"),
                    TransactionVersionId("version-ambiguous-v1"),
                    PostingSetId("posting-set-ambiguous"),
                    PostingId("posting-expense-ambiguous"),
                    listOf(PostingId("posting-asset-ambiguous"), PostingId("posting-liability-ambiguous")),
                ),
                confirmationId = "confirmation-ambiguous",
                confirmedStatusId = "status-ambiguous-confirmed",
                relationId = "relation-ambiguous",
                assetEvidenceLinkId = "match-asset-ambiguous",
                assetReconciliationId = "reconciliation-asset-ambiguous",
                liabilityReconciliationId = "reconciliation-liability-ambiguous",
            ))
            assertIs<Rg04ImportExecutionResult.Accepted>(executor.execute(secondConfirm))
            val before = counts(database)
            assertEquals(
                Rg04ImportExecutionResult.Rejected(Rg04ImportExecutionError.AMBIGUOUS_MIRROR_TARGET),
                executor.execute(mirror.copy(snapshot = mirror.snapshot.copy(
                    requestId = RequestId("request-ambiguous-mirror"),
                    sourceId = Rg04SourceId("source-ambiguous-mirror"),
                    evidenceId = Rg04EvidenceId("evidence-ambiguous-mirror"),
                    evidenceLinkId = "match-ambiguous-mirror",
                ))),
            )
            assertEquals(before, counts(database))
        }
    }
}

private fun concurrentExecute(
    url: String,
    case: Rg04RawJsonCase,
    operations: List<Rg04DecodedImportOperation>,
): List<Rg04ImportExecutionResult> {
    val pool = Executors.newFixedThreadPool(operations.size)
    val ready = CountDownLatch(operations.size)
    val start = CountDownLatch(1)
    return try {
        val futures = operations.map { operation ->
            pool.submit<Rg04ImportExecutionResult> {
                ready.countDown()
                check(start.await(5, TimeUnit.SECONDS))
                JdbcSqliteDriver(url).use { driver ->
                    ExecuteRg04ImportOperation(
                        SqlDelightRg04ImportStore(LedgerDatabase(driver), driver, case.catalog),
                    ).execute(operation)
                }
            }
        }
        check(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        futures.map { it.get(10, TimeUnit.SECONDS) }
    } finally {
        pool.shutdownNow()
    }
}

internal fun decodedCase(): Rg04RawJsonCase = assertIs<Rg04RawJsonDecodeResult.Success>(
    decodeRg04RawJson(Files.readString(repositoryFile("golden/rules/rg-04.json"))),
).value

private fun formalCounts(database: LedgerDatabase) = listOf(
    database.ledgerQueries.countTransactions().executeAsOne(),
    database.ledgerQueries.countVersions().executeAsOne(),
    database.ledgerQueries.countPostings().executeAsOne(),
    database.ledgerQueries.countRg04Relations().executeAsOne(),
    database.ledgerQueries.countRg04RelationMembers().executeAsOne(),
)

private fun counts(database: LedgerDatabase) = formalCounts(database) + listOf(
    database.ledgerQueries.countRg04ImportRequests().executeAsOne(),
    database.ledgerQueries.countRg04ImportSources().executeAsOne(),
    database.ledgerQueries.countRg04ImportEvidence().executeAsOne(),
    database.ledgerQueries.countRg04ImportCandidates().executeAsOne(),
    database.ledgerQueries.countRg04ImportConfirmations().executeAsOne(),
    database.ledgerQueries.countRg04ImportMatches().executeAsOne(),
    database.ledgerQueries.countRg04ImportReceipts().executeAsOne(),
)

private fun repositoryFile(relative: String): Path {
    var current = Path.of("").toAbsolutePath()
    while (true) {
        val candidate = current.resolve(relative)
        if (Files.exists(candidate)) return candidate
        current = current.parent ?: error("repository root not found")
    }
}
