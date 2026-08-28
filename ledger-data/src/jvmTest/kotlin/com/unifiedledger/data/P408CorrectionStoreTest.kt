package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.P408ConfirmLinkRequest
import com.unifiedledger.application.P408CorrectLinkRequest
import com.unifiedledger.application.P408CorrectionCommitPort
import com.unifiedledger.application.P408CorrectionReason
import com.unifiedledger.application.P408CorrectionResultState
import com.unifiedledger.application.P408EvidenceFacts
import com.unifiedledger.application.P408EvidenceProjectionPort
import com.unifiedledger.application.P408EvidenceResponsibility
import com.unifiedledger.application.P408MatchDisposition
import com.unifiedledger.application.P408Matcher
import com.unifiedledger.application.P408MaterializationRequest
import com.unifiedledger.application.P408MaterializeResult
import com.unifiedledger.application.P408NormalizedProjectionFacts
import com.unifiedledger.application.P408PostingFacts
import com.unifiedledger.application.P408RawEvidenceFacts
import com.unifiedledger.application.P408ReconciliationResult
import com.unifiedledger.application.P408ReconciliationStatus
import com.unifiedledger.application.P408SuccessorLinkFacts
import com.unifiedledger.application.P408TemporalComponents
import com.unifiedledger.application.P408TemporalEvidence
import com.unifiedledger.data.SqlDelightEvidenceProjectionStore.EnsureReadyResult
import com.unifiedledger.data.db.LedgerDatabase
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.CountDownLatch
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * D-113 correction/successor invalidation acceptance matrix (spec section 11,
 * TP-01..TP-17 at the store level). Fixtures are all synthetic; money amounts
 * are integer minor units; every correction runs through the single
 * [P408CorrectionCommitPort] transaction.
 */
class P408CorrectionStoreTest {
    // ------------------------------------------------------------------ TP-01

    @Test
    fun tp01VersionReplacedSameFactsCreatesSuccessorChainAndKeepsBalances() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seedBase(driver)
            val store = SqlDelightP408ReconciliationStore(database, driver)
            assertIs<P408ReconciliationResult.Accepted>(store.confirmLink(confirmRequest()))

            seedReplacementVersion(driver, newOutAccountId = "account-bank-a")

            val before = financialSnapshot(driver)
            val correction = SqlDelightCorrectionStore(database, driver)
            val accepted =
                assertIs<P408ReconciliationResult.Accepted>(
                    correction.correct(
                        correctRequestForSuccessor(
                            requestId = "correction-a",
                            previousLinkId = "link-a",
                            successorPostingId = "posting-a2",
                            accountId = "account-bank-a",
                            projectionId = "proj-evidence-a",
                            successorLinkId = "link-b",
                            reconciliationId = "reconciliation-posting-a2",
                        ),
                    ),
                )
            assertEquals("link-b", accepted.receipt.linkId)
            assertEquals("reconciliation-posting-a2", accepted.receipt.reconciliationId)
            assertEquals(2L, accepted.receipt.historySequence)

            // Predecessor link history: active(seq1) then invalidated(seq2).
            assertEquals(
                listOf(
                    Triple("active", "confirmed", 1L),
                    Triple("invalidated", "posting_replaced", 2L),
                ),
                linkHistory(driver, "link-a"),
            )
            // Successor born active/confirmed.
            assertEquals(listOf(Triple("active", "confirmed", 1L)), linkHistory(driver, "link-b"))

            // Single active authority per evidence, posting side and report.
            assertEquals(listOf("link-b"), activeLinksForEvidence(driver, "evidence-a"))
            val reportAfter = store.readReconciliationReport("ledger-a")
            assertEquals(
                listOf("posting-a2" to listOf("link-b"), "posting-b2" to emptyList()),
                reportAfter.map { it.postingId to it.activeLinkIds },
            )
            assertEquals(
                P408ReconciliationStatus.CHECKED,
                reportAfter.first { it.postingId == "posting-a2" }.status,
            )

            // Zero projection writes when the authority content already matches.
            assertEquals(1L, count(driver, "SELECT count(*) FROM evidence_projection"))
            assertEquals(
                0L,
                count(driver, "SELECT count(*) FROM evidence_projection WHERE superseded_by_projection_id IS NOT NULL"),
            )

            // Financial invariance: formal rows, balances and versions unchanged.
            assertEquals(before, financialSnapshot(driver))
        } finally {
            driver.close()
        }
    }

    // ------------------------------------------------------------------ TP-02

    @Test
    fun tp02ReExpressToDifferentTargetSupersedesProjectionAndLinksSuccessor() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seedBase(driver)
            val store = SqlDelightP408ReconciliationStore(database, driver)
            assertIs<P408ReconciliationResult.Accepted>(store.confirmLink(confirmRequest()))

            seedReplacementVersion(driver, newOutAccountId = "account-bank-c")

            val correction = SqlDelightCorrectionStore(database, driver)
            val reExpressResult =
                correction.correct(
                    correctRequestForSuccessor(
                        requestId = "correction-reexpress",
                        previousLinkId = "link-a",
                        successorPostingId = "posting-a2",
                        accountId = "account-bank-c",
                        projectionId = "proj-evidence-a-2",
                        successorLinkId = "link-b",
                        reconciliationId = "reconciliation-posting-a2",
                    ),
                )
            if (reExpressResult !is P408ReconciliationResult.Accepted) {
                throw AssertionError("re-expression correction failed: $reExpressResult")
            }

            // Old authority frozen as superseded; new current READY row present.
            assertEquals(
                1L,
                count(driver, "SELECT count(*) FROM evidence_projection WHERE projection_id='proj-evidence-a' AND superseded_by_projection_id='proj-evidence-a-2'"),
            )
            val current =
                database.ledgerQueries
                    .selectP408EvidenceProjection("ledger-a", "evidence-a")
                    .executeAsOne()
            assertEquals("proj-evidence-a-2", current.projection_id)
            assertEquals("account-bank-c", current.target_account_id)
            assertEquals("READY", current.state)
            // Consumption gate reads only the current authority.
            val readBack = SqlDelightEvidenceProjectionStore(database, driver).readProjection("ledger-a", "evidence-a")
            assertEquals("proj-evidence-a-2", readBack?.projectionId)

            // A second correction in the same chain with the SAME content re-links
            // without growing the projection chain.
            assertIs<P408ReconciliationResult.Accepted>(
                correction.correct(
                    correctRequestForSuccessor(
                        requestId = "correction-reexpress-2",
                        previousLinkId = "link-b",
                        successorPostingId = "posting-a2",
                        accountId = "account-bank-c",
                        projectionId = "proj-evidence-a-2",
                        successorLinkId = "link-c",
                        reconciliationId = "reconciliation-posting-a2",
                        confirmedAt = "2026-08-10T15:00:00+08:00",
                    ),
                ),
            )
            assertEquals(2L, count(driver, "SELECT count(*) FROM evidence_projection"))
            assertEquals(
                1L,
                count(driver, "SELECT count(*) FROM evidence_projection WHERE superseded_by_projection_id IS NOT NULL"),
            )
        } finally {
            driver.close()
        }
    }

    // ------------------------------------------------------------------ TP-03

    @Test
    fun tp03EquivalentReplayReturnsOriginalReceiptAndAppendsNothing() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seedBase(driver)
            val store = SqlDelightP408ReconciliationStore(database, driver)
            assertIs<P408ReconciliationResult.Accepted>(store.confirmLink(confirmRequest()))
            seedReplacementVersion(driver, newOutAccountId = "account-bank-a")

            val correction = SqlDelightCorrectionStore(database, driver)
            val request =
                correctRequestForSuccessor(
                    requestId = "correction-replay",
                    previousLinkId = "link-a",
                    successorPostingId = "posting-a2",
                    accountId = "account-bank-a",
                    projectionId = "proj-evidence-a",
                    successorLinkId = "link-b",
                    reconciliationId = "reconciliation-posting-a2",
                )
            val accepted = assertIs<P408ReconciliationResult.Accepted>(correction.correct(request))

            val replay = assertIs<P408ReconciliationResult.NoChange>(correction.correct(request))
            assertEquals(accepted.receipt, replay.receipt)

            // Output-id retry: same content, different generated ids -> NoChange.
            val outputIdsRetry =
                request.copy(
                    successorLinkId = "link-b-retry",
                    successorCreatedAt = "2026-08-10T16:00:00+08:00",
                    reconciliationId = "reconciliation-a2-retry",
                )
            val identityReplay = assertIs<P408ReconciliationResult.NoChange>(correction.correct(outputIdsRetry))
            assertEquals(accepted.receipt, identityReplay.receipt)

            // Zero new writes on replay (claim baselines: request-a + the correction).
            assertEquals(2L, count(driver, "SELECT count(*) FROM evidence_link"))
            assertEquals(2L, count(driver, "SELECT count(*) FROM evidence_link_history WHERE link_id='link-a'"))
            assertEquals(2L, count(driver, "SELECT count(*) FROM reconciliation_request"))
            assertEquals(2L, count(driver, "SELECT count(*) FROM reconciliation_receipt"))
        } finally {
            driver.close()
        }
    }

    // ------------------------------------------------------------------ TP-04

    @Test
    fun tp04ChangedRetryIsTypedConflictWithZeroWrites() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seedBase(driver)
            val store = SqlDelightP408ReconciliationStore(database, driver)
            assertIs<P408ReconciliationResult.Accepted>(store.confirmLink(confirmRequest()))
            seedReplacementVersion(driver, newOutAccountId = "account-bank-a")

            val correction = SqlDelightCorrectionStore(database, driver)
            assertIs<P408ReconciliationResult.Accepted>(
                correction.correct(
                    correctRequestForSuccessor(
                        requestId = "correction-conflict",
                        previousLinkId = "link-a",
                        successorPostingId = "posting-a2",
                        accountId = "account-bank-a",
                        projectionId = "proj-evidence-a",
                        successorLinkId = "link-b",
                        reconciliationId = "reconciliation-posting-a2",
                    ),
                ),
            )
            val changed =
                correctRequestForSuccessor(
                    requestId = "correction-conflict",
                    previousLinkId = "link-a",
                    successorPostingId = "posting-b2",
                    accountId = "account-platform-b",
                    direction = "in",
                    responsibility = P408EvidenceResponsibility.DESTINATION_ASSET_POSTING,
                    projectionId = "proj-evidence-a-2",
                    successorLinkId = "link-c",
                    reconciliationId = "reconciliation-posting-b2",
                    confirmedAt = "2026-08-10T15:00:00+08:00",
                )
            val rejected = assertIs<P408ReconciliationResult.Rejected>(correction.correct(changed))
            assertEquals("P408_CORRECTION_SNAPSHOT_MISMATCH", rejected.code)
            assertEquals(2L, count(driver, "SELECT count(*) FROM evidence_link"))
            assertEquals(2L, count(driver, "SELECT count(*) FROM reconciliation_request"))
            assertEquals(2L, count(driver, "SELECT count(*) FROM reconciliation_receipt"))
        } finally {
            driver.close()
        }
    }

    // ------------------------------------------------------------------ TP-05/06

    @Test
    fun tp05InvalidateOnlyMissingKeepsNoSuccessorAndAdvancesState() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seedBase(driver)
            val store = SqlDelightP408ReconciliationStore(database, driver)
            assertIs<P408ReconciliationResult.Accepted>(store.confirmLink(confirmRequest()))

            val correction = SqlDelightCorrectionStore(database, driver)
            val accepted =
                assertIs<P408ReconciliationResult.Accepted>(
                    correction.correct(
                        correctRequestForInvalidationOnly(
                            requestId = "correction-missing",
                            reason = P408CorrectionReason.CORRECTED,
                            resultState = P408CorrectionResultState.MISSING,
                            affectedPostingId = "posting-a",
                            previousLinkId = "link-a",
                            reconciliationId = "reconciliation-posting-a",
                        ),
                    ),
                )
            assertEquals(null, accepted.receipt.linkId)
            assertEquals(3L, accepted.receipt.historySequence)

            assertEquals(
                listOf(Triple("active", "confirmed", 1L), Triple("invalidated", "corrected", 2L)),
                linkHistory(driver, "link-a"),
            )
            assertEquals(1L, count(driver, "SELECT count(*) FROM evidence_link"))
            assertEquals("MISSING", reconciliationStatus(driver, "posting-a"))
            val reportAfter = store.readReconciliationReport("ledger-a")
            assertEquals(
                P408ReconciliationStatus.MISSING,
                reportAfter.first { it.postingId == "posting-a" }.status,
            )
            assertEquals(emptyList<String>(), reportAfter.first { it.postingId == "posting-a" }.activeLinkIds)
            assertEquals(
                listOf(1L to "PENDING", 2L to "CHECKED", 3L to "MISSING"),
                reconciliationHistory(driver, "reconciliation-posting-a"),
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun tp06InvalidateOnlyDifferenceExpressesExplicitDifference() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seedBase(driver)
            val store = SqlDelightP408ReconciliationStore(database, driver)
            assertIs<P408ReconciliationResult.Accepted>(store.confirmLink(confirmRequest()))

            val correction = SqlDelightCorrectionStore(database, driver)
            assertIs<P408ReconciliationResult.Accepted>(
                correction.correct(
                    correctRequestForInvalidationOnly(
                        requestId = "correction-difference",
                        reason = P408CorrectionReason.CORRECTED,
                        resultState = P408CorrectionResultState.DIFFERENCE,
                        affectedPostingId = "posting-a",
                        previousLinkId = "link-a",
                        reconciliationId = "reconciliation-posting-a",
                    ),
                ),
            )
            assertEquals("DIFFERENCE", reconciliationStatus(driver, "posting-a"))
            assertEquals(
                P408ReconciliationStatus.DIFFERENCE,
                store.readReconciliationReport("ledger-a").first { it.postingId == "posting-a" }.status,
            )
            // Unaffected transaction leg keeps its own state: posting-b has no row in
            // the fresh IN_MEMORY schema (no seed) and the correction never touches it.
            assertEquals(null, reconciliationStatus(driver, "posting-b"))
        } finally {
            driver.close()
        }
    }

    // ------------------------------------------------------------------ TP-07

    @Test
    fun tp07IllegalCorrectionsAreTypedRejectedWithZeroWrites() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seedBase(driver)
            val store = SqlDelightP408ReconciliationStore(database, driver)
            assertIs<P408ReconciliationResult.Accepted>(store.confirmLink(confirmRequest()))
            val correction = SqlDelightCorrectionStore(database, driver)

            // (a) missing predecessor.
            val ghost =
                assertIs<P408ReconciliationResult.Rejected>(
                    correction.correct(
                        correctRequestForInvalidationOnly(
                            requestId = "correction-ghost",
                            affectedPostingId = "posting-a",
                            previousLinkId = "link-ghost",
                            reconciliationId = "reconciliation-posting-a",
                        ),
                    ),
                )
            assertEquals("P408_INVALIDATE_LINK_NOT_ACTIVE", ghost.code)

            // (b) predecessor belongs to a different evidence.
            val wrongEvidence =
                assertIs<P408ReconciliationResult.Rejected>(
                    correction.correct(
                        correctRequestForInvalidationOnly(
                            requestId = "correction-wrong-evidence",
                            evidenceId = "evidence-b",
                            affectedPostingId = "posting-a",
                            previousLinkId = "link-a",
                            reconciliationId = "reconciliation-posting-a",
                        ),
                    ),
                )
            assertEquals("P408_INVALIDATE_LINK_NOT_ACTIVE", wrongEvidence.code)

            // (c) affected posting not eligible (EXPENSE leg is not an
            // ACCOUNT_TRANSFER current posting).
            val ineligible =
                assertIs<P408ReconciliationResult.Rejected>(
                    correction.correct(
                        correctRequestForInvalidationOnly(
                            requestId = "correction-ineligible",
                            affectedPostingId = "posting-expense",
                            previousLinkId = "link-a",
                            reconciliationId = "reconciliation-posting-expense",
                        ),
                    ),
                )
            assertEquals("P408_CORRECTION_AFFECTED_POSTING_MISMATCH", ineligible.code)

            // (d) successful invalidation, then a second invalidation of the same
            // (now terminal) link is rejected.
            assertIs<P408ReconciliationResult.Accepted>(
                correction.correct(
                    correctRequestForInvalidationOnly(
                        requestId = "correction-first-invalidate",
                        affectedPostingId = "posting-a",
                        previousLinkId = "link-a",
                        reconciliationId = "reconciliation-posting-a",
                    ),
                ),
            )
            val secondInvalidate =
                assertIs<P408ReconciliationResult.Rejected>(
                    correction.correct(
                        correctRequestForInvalidationOnly(
                            requestId = "correction-second-invalidate",
                            affectedPostingId = "posting-a",
                            previousLinkId = "link-a",
                            reconciliationId = "reconciliation-posting-a",
                        ),
                    ),
                )
            assertEquals("P408_INVALIDATE_LINK_NOT_ACTIVE", secondInvalidate.code)

            // (e) half-seeded source returns the confirm-family UNRESOLVED outcome on
            // the CHECKED path (QUAL-004 ordering: source presence precedes the
            // projection authority gate).
            driver.execute(null, "INSERT INTO import_request VALUES ('ledger-a','import-half','intake')", 0)
            driver.execute(
                null,
                "INSERT INTO import_source_record(ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness, amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token) VALUES ('ledger-a','source-half','import-half','batch-half',0,'ordinary_flow_source','hash-half',1,'valid_incomplete',NULL,NULL,NULL,NULL,NULL,NULL)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO import_evidence VALUES ('ledger-a','evidence-half','source-half','source_observation','2026-08-10T12:00:01+08:00')",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO reconciliation_request VALUES ('ledger-a','request-half-link','confirm_link','fp-half','ACCEPTED',NULL)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO evidence_link(ledger_id, link_id, evidence_id, posting_id, transaction_id, responsibility, basis_version, match_basis, candidate_id, request_id, created_at) VALUES ('ledger-a','link-half','evidence-half','posting-a','tx-a','real_account_posting',2,'amount,currency,direction,account,occurred_at_window','candidate-half','request-half-link','2026-08-10T13:00:00+08:00')",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO evidence_link_history VALUES ('ledger-a','link-half',1,'active','confirmed','request-half-link','2026-08-10T13:00:00+08:00')",
                0,
            )
            val halfSeeded =
                assertIs<P408ReconciliationResult.Rejected>(
                    correction.correct(
                        correctRequestForSuccessor(
                            requestId = "correction-half-seeded",
                            previousLinkId = "link-half",
                            successorPostingId = "posting-a",
                            accountId = "account-bank-a",
                            projectionId = "proj-evidence-a",
                            successorLinkId = "link-half-succ",
                            reconciliationId = "reconciliation-posting-a",
                        ).copy(evidenceId = "evidence-half"),
                    ),
                )
            assertEquals("P408_SOURCE_FACT_UNRESOLVED", halfSeeded.code)

            // Only the successful invalidation left its own rows; the confirm claim and the
            // half-seeded fixture request are the baseline, and every rejected
            // attempt (including half-seeded) rolled back its claim.
            assertEquals(3L, count(driver, "SELECT count(*) FROM reconciliation_request"))
            assertEquals(2L, count(driver, "SELECT count(*) FROM reconciliation_receipt"))
            assertEquals(2L, count(driver, "SELECT count(*) FROM evidence_link"))
        } finally {
            driver.close()
        }
    }

    // ------------------------------------------------------------------ TP-08

    @Test
    fun tp08InjectedFailuresRollBackEveryCorrectionOwnerAndRetryAccepts() {
        // Stage A - CORRECTION_AFTER_INVALIDATION: the successor-link insert is the
        // first write after the invalidation event. A pre-occupied link id makes that
        // insert fail; the entire transaction (claim, invalidation event, snapshot)
        // must roll back. The link face pins the generic constraint code (SPEC-005).
        newCorrectionScenario().let { scenario ->
            try {
                scenario.driver.execute(
                    null,
                    "INSERT INTO evidence_link(ledger_id, link_id, evidence_id, posting_id, transaction_id, responsibility, basis_version, match_basis, candidate_id, request_id, created_at) VALUES ('ledger-a','link-b','evidence-a','posting-a2','tx-a','real_account_posting',2,'amount,currency,direction,account,occurred_at_window','candidate-collision','request-a','2026-08-10T13:00:00+08:00')",
                    0,
                )
                val stageA =
                    assertIs<P408ReconciliationResult.Rejected>(
                        scenario.correction.correct(
                            correctRequestForSuccessor(
                                requestId = "correction-inject-link-collision",
                                previousLinkId = "link-a",
                                successorPostingId = "posting-a2",
                                accountId = "account-bank-a",
                                projectionId = "proj-evidence-a",
                                successorLinkId = "link-b",
                                reconciliationId = "reconciliation-posting-a2",
                            ),
                        ),
                    )
                assertEquals("P408_RECONCILIATION_CONSTRAINT_VIOLATION", stageA.code)
                assertEquals(1L, count(scenario.driver, "SELECT count(*) FROM reconciliation_request"))
                assertEquals(1L, count(scenario.driver, "SELECT count(*) FROM evidence_link_history WHERE link_id='link-a'"))
                assertEquals(0L, count(scenario.driver, "SELECT count(*) FROM reconciliation_correction_snapshot"))
                assertEquals(1L, count(scenario.driver, "SELECT count(*) FROM evidence_projection"))
            } finally {
                scenario.driver.close()
            }
        }
        // Corrected retries run in an obstacle-free equivalent scenario (the guard
        // triggers forbid in-place cleanup, so "corrected" = obstacle absent).
        newCorrectionScenario().let { scenario ->
            try {
                val retryA =
                    assertIs<P408ReconciliationResult.Accepted>(
                        scenario.correction.correct(
                            correctRequestForSuccessor(
                                requestId = "correction-inject-retry-a",
                                previousLinkId = "link-a",
                                successorPostingId = "posting-a2",
                                accountId = "account-bank-a",
                                projectionId = "proj-evidence-a",
                                successorLinkId = "link-b-retry",
                                reconciliationId = "reconciliation-posting-a2",
                            ),
                        ),
                    )
                assertEquals("link-b-retry", retryA.receipt.linkId)
            } finally {
                scenario.driver.close()
            }
        }

        // Stage B - CORRECTION_AFTER_SUCCESSOR_LINK: the reconciliation-history insert
        // follows the successor link; a pre-occupied next history sequence makes it
        // fail and the successor link must roll back with the whole transaction.
        newCorrectionScenario().let { scenario ->
            try {
                assertIs<P408ReconciliationResult.Accepted>(
                    scenario.correction.correct(
                        correctRequestForSuccessor(
                            requestId = "correction-b-first",
                            previousLinkId = "link-a",
                            successorPostingId = "posting-a2",
                            accountId = "account-bank-a",
                            projectionId = "proj-evidence-a",
                            successorLinkId = "link-b",
                            reconciliationId = "reconciliation-posting-a2",
                        ),
                    ),
                )
                scenario.driver.execute(
                    null,
                    "INSERT INTO posting_reconciliation_history(ledger_id, reconciliation_id, sequence, status, evidence_link_id, request_id, occurred_at) VALUES ('ledger-a','reconciliation-posting-a2',3,'CHECKED','link-b','request-a','2026-08-10T14:30:00+08:00')",
                    0,
                )
                val stageB =
                    assertIs<P408ReconciliationResult.Rejected>(
                        scenario.correction.correct(
                            correctRequestForSuccessor(
                                requestId = "correction-inject-history-collision",
                                previousLinkId = "link-b",
                                successorPostingId = "posting-a2",
                                accountId = "account-bank-a",
                                projectionId = "proj-evidence-a",
                                successorLinkId = "link-c",
                                reconciliationId = "reconciliation-posting-a2",
                                confirmedAt = "2026-08-10T15:00:00+08:00",
                            ),
                        ),
                    )
                assertEquals("P408_RECONCILIATION_CONSTRAINT_VIOLATION", stageB.code)
                assertEquals(0L, count(scenario.driver, "SELECT count(*) FROM evidence_link WHERE link_id='link-c'"))
                assertEquals(1L, count(scenario.driver, "SELECT count(*) FROM reconciliation_correction_snapshot"))
                // The predecessor of stage B is link-b: its invalidation event rolled
                // back, so it keeps only its birth history row (link-a keeps the 2
                // rows created by the stage-B first correction).
                assertEquals(1L, count(scenario.driver, "SELECT count(*) FROM evidence_link_history WHERE link_id='link-b'"))
                assertEquals(2L, count(scenario.driver, "SELECT count(*) FROM evidence_link_history WHERE link_id='link-a'"))
            } finally {
                scenario.driver.close()
            }
        }
        newCorrectionScenario().let { scenario ->
            try {
                assertIs<P408ReconciliationResult.Accepted>(
                    scenario.correction.correct(
                        correctRequestForSuccessor(
                            requestId = "correction-b-first",
                            previousLinkId = "link-a",
                            successorPostingId = "posting-a2",
                            accountId = "account-bank-a",
                            projectionId = "proj-evidence-a",
                            successorLinkId = "link-b",
                            reconciliationId = "reconciliation-posting-a2",
                        ),
                    ),
                )
                val retryB =
                    assertIs<P408ReconciliationResult.Accepted>(
                        scenario.correction.correct(
                            correctRequestForSuccessor(
                                requestId = "correction-b-retry",
                                previousLinkId = "link-b",
                                successorPostingId = "posting-a2",
                                accountId = "account-bank-a",
                                projectionId = "proj-evidence-a",
                                successorLinkId = "link-c",
                                reconciliationId = "reconciliation-posting-a2",
                                confirmedAt = "2026-08-10T15:00:00+08:00",
                            ),
                        ),
                    )
                assertEquals("link-c", retryB.receipt.linkId)
            } finally {
                scenario.driver.close()
            }
        }

        // Stage C - CORRECTION_AFTER_PROJECTION_SUPERCEDE: the successor projection
        // insert follows the controlled supersede. The projection id is pre-occupied
        // by a DIFFERENT evidence's row (per-evidence partial index stays intact), so
        // the failure is pinned to the projection face (SPEC-005).
        newCorrectionScenario().let { scenario ->
            try {
                scenario.driver.execute(
                    null,
                    "UPDATE posting SET account_id='account-bank-c' WHERE ledger_id='ledger-a' AND posting_id='posting-a2'",
                    0,
                )
                scenario.driver.execute(
                    null,
                    "INSERT INTO evidence_projection(ledger_id, projection_id, evidence_id, source_id, source_hash, target_account_id, currency_code, currency_precision, raw_amount_minor, raw_currency_precision, normalized_amount_minor, direction_token, state, rejection_code, rule_id, rule_version, materialization_request_id, materialized_at) VALUES ('ledger-a','proj-evidence-a-2','evidence-b','source-b','hash-b','account-platform-b','CNY',2,1000,2,1000,'in','READY',NULL,'p408_evidence_projection_v1',1,'request-free-holder','2026-08-10T15:00:00+08:00')",
                    0,
                )
                val stageC =
                    assertIs<P408ReconciliationResult.Rejected>(
                        scenario.correction.correct(
                            correctRequestForSuccessor(
                                requestId = "correction-inject-projection-collision",
                                previousLinkId = "link-a",
                                successorPostingId = "posting-a2",
                                accountId = "account-bank-c",
                                projectionId = "proj-evidence-a-2",
                                successorLinkId = "link-b",
                                reconciliationId = "reconciliation-posting-a2",
                                confirmedAt = "2026-08-10T16:00:00+08:00",
                            ),
                        ),
                    )
                assertEquals("P408_CORRECTION_PROJECTION_CONFLICT", stageC.code)
                assertEquals(
                    1L,
                    count(scenario.driver, "SELECT count(*) FROM evidence_projection WHERE projection_id='proj-evidence-a' AND superseded_by_projection_id IS NULL"),
                )
                assertEquals(0L, count(scenario.driver, "SELECT count(*) FROM evidence_link WHERE link_id='link-b'"))
                assertEquals(0L, count(scenario.driver, "SELECT count(*) FROM reconciliation_correction_snapshot"))
            } finally {
                scenario.driver.close()
            }
        }
        newCorrectionScenario().let { scenario ->
            try {
                scenario.driver.execute(
                    null,
                    "UPDATE posting SET account_id='account-bank-c' WHERE ledger_id='ledger-a' AND posting_id='posting-a2'",
                    0,
                )
                val retryC =
                    assertIs<P408ReconciliationResult.Accepted>(
                        scenario.correction.correct(
                            correctRequestForSuccessor(
                                requestId = "correction-c-retry",
                                previousLinkId = "link-a",
                                successorPostingId = "posting-a2",
                                accountId = "account-bank-c",
                                projectionId = "proj-evidence-a-2",
                                successorLinkId = "link-b",
                                reconciliationId = "reconciliation-posting-a2",
                                confirmedAt = "2026-08-10T16:00:00+08:00",
                            ),
                        ),
                    )
                assertEquals("link-b", retryC.receipt.linkId)
                assertEquals(2L, count(scenario.driver, "SELECT count(*) FROM evidence_projection"))
                assertEquals(
                    1L,
                    count(scenario.driver, "SELECT count(*) FROM evidence_projection WHERE projection_id='proj-evidence-a' AND superseded_by_projection_id='proj-evidence-a-2'"),
                )
            } finally {
                scenario.driver.close()
            }
        }
    }

    // ------------------------------------------------------------------ TP-09

    @Test
    fun tp09CorrectionNeverChangesBalancesFormalRowsOrReportFinancialDimensions() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seedBase(driver)
            val store = SqlDelightP408ReconciliationStore(database, driver)
            assertIs<P408ReconciliationResult.Accepted>(store.confirmLink(confirmRequest()))
            seedReplacementVersion(driver, newOutAccountId = "account-bank-c")

            val before = financialSnapshot(driver)
            assertEquals(3, before.balances.size)

            val correction = SqlDelightCorrectionStore(database, driver)
            assertIs<P408ReconciliationResult.Accepted>(
                correction.correct(
                    correctRequestForSuccessor(
                        requestId = "correction-oracle",
                        previousLinkId = "link-a",
                        successorPostingId = "posting-a2",
                        accountId = "account-bank-c",
                        projectionId = "proj-evidence-a-2",
                        successorLinkId = "link-b",
                        reconciliationId = "reconciliation-posting-a2",
                    ),
                ),
            )

            val after = financialSnapshot(driver)
            assertEquals(before.rows, after.rows)
            assertEquals(before.balances, after.balances)
            assertEquals(before.currentVersionCount, after.currentVersionCount)
            assertEquals(before.versions, after.versions)
        } finally {
            driver.close()
        }
    }

    // ------------------------------------------------------------------ TP-10

    @Test
    fun tp10SingleActiveAuthorityAcrossCorrections() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seedBase(driver, includeSecondPosting = false)
            val store = SqlDelightP408ReconciliationStore(database, driver)
            assertIs<P408ReconciliationResult.Accepted>(store.confirmLink(confirmRequest()))
            seedReplacementVersion(driver, newOutAccountId = "account-bank-a")

            val correction = SqlDelightCorrectionStore(database, driver)
            assertIs<P408ReconciliationResult.Accepted>(
                correction.correct(
                    correctRequestForSuccessor(
                        requestId = "correction-single",
                        previousLinkId = "link-a",
                        successorPostingId = "posting-a2",
                        accountId = "account-bank-a",
                        projectionId = "proj-evidence-a",
                        successorLinkId = "link-b",
                        reconciliationId = "reconciliation-posting-a2",
                    ),
                ),
            )
            assertEquals(listOf("link-b"), activeLinksForEvidence(driver, "evidence-a"))
            assertEquals(listOf("link-b"), activeLinksForPostingResponsibility(driver, "posting-a2"))
            // Exactly one ACTIVE authority: the predecessor's invalidation happened
            // in the same transaction; only the successor is active today.
            assertEquals(1, activeLinksForEvidence(driver, "evidence-a").size)
        } finally {
            driver.close()
        }
    }

    @Test
    fun tp10ConcurrentCorrectionsHaveSingleWinnerAndTypedLoser() {
        val path = Files.createTempFile("p408-corr-concurrent-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            sqliteProps().let { props ->
                JdbcSqliteDriver(url, props).use { driver ->
                    LedgerDatabase.Schema.create(driver)
                    seedBase(driver)
                    val store = SqlDelightP408ReconciliationStore(LedgerDatabase(driver), driver)
                    assertIs<P408ReconciliationResult.Accepted>(store.confirmLink(confirmRequest()))
                    seedReplacementVersion(driver, newOutAccountId = "account-bank-a")
                }
            }
            val results = java.util.Collections.synchronizedList(mutableListOf<P408ReconciliationResult>())
            val start = CountDownLatch(1)
            val runner = { suffix: String ->
                sqliteProps().let { props ->
                    JdbcSqliteDriver(url, props).use { driver ->
                        val correction = SqlDelightCorrectionStore(LedgerDatabase(driver), driver)
                        start.await()
                        results.add(
                            correction.correct(
                                correctRequestForSuccessor(
                                    requestId = "correction-concurrent-$suffix",
                                    previousLinkId = "link-a",
                                    successorPostingId = "posting-a2",
                                    accountId = "account-bank-a",
                                    projectionId = "proj-evidence-a",
                                    successorLinkId = "link-concurrent-$suffix",
                                    reconciliationId = "reconciliation-posting-a2",
                                ),
                            ),
                        )
                    }
                }
            }
            val threads = listOf(Thread { runner("one") }, Thread { runner("two") })
            threads.forEach { it.start() }
            start.countDown()
            threads.forEach { it.join(30_000) }

            // Exactly one winner; the loser is a typed zero-residue rejection.
            assertEquals(2, results.size)
            assertEquals(1, results.count { it is P408ReconciliationResult.Accepted })
            assertEquals(1, results.count { it is P408ReconciliationResult.Rejected })
            val loser = results.filterIsInstance<P408ReconciliationResult.Rejected>().single().code
            assertTrue(
                loser == "P408_INVALIDATE_LINK_NOT_ACTIVE" ||
                    loser == "P408_CORRECTION_SNAPSHOT_MISMATCH" ||
                    loser == "P408_RECONCILIATION_CONSTRAINT_VIOLATION",
                "unexpected loser code $loser",
            )

            // Winner-only residue: one correction chain on the evidence.
            sqliteProps().let { props ->
                JdbcSqliteDriver(url, props).use { driver ->
                    assertEquals(1L, count(driver, "SELECT count(*) FROM reconciliation_request WHERE operation='invalidate_link'"))
                    assertEquals(1L, count(driver, "SELECT count(*) FROM reconciliation_correction_snapshot"))
                    assertEquals(2L, count(driver, "SELECT count(*) FROM evidence_link"))
                    // Exactly one ACTIVE authority (the loser never committed an
                    // invalidation; the predecessor's birth row stays in history).
                    assertEquals(
                        1L,
                        count(
                            driver,
                            "SELECT count(*) FROM evidence_link link JOIN evidence_link_history h ON h.ledger_id=link.ledger_id AND h.link_id=link.link_id WHERE link.ledger_id='ledger-a' AND h.state='active' AND h.sequence=(SELECT max(h2.sequence) FROM evidence_link_history h2 WHERE h2.ledger_id=link.ledger_id AND h2.link_id=link.link_id)",
                        ),
                    )
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    // ------------------------------------------------------------------ TP-11

    @Test
    fun tp11GuardsAbortUnconditionalMutationAndKeepSingleActiveAuthority() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seedBase(driver)
            val store = SqlDelightP408ReconciliationStore(database, driver)
            assertIs<P408ReconciliationResult.Accepted>(store.confirmLink(confirmRequest()))
            seedReplacementVersion(driver, newOutAccountId = "account-bank-a")

            // A real correction first: the guard probes below need rows to exist in
            // every guarded table (link history, link, projection, snapshot).
            val correction = SqlDelightCorrectionStore(database, driver)
            assertIs<P408ReconciliationResult.Accepted>(
                correction.correct(
                    correctRequestForSuccessor(
                        requestId = "correction-guards",
                        previousLinkId = "link-a",
                        successorPostingId = "posting-a2",
                        accountId = "account-bank-a",
                        projectionId = "proj-evidence-a",
                        successorLinkId = "link-b",
                        reconciliationId = "reconciliation-posting-a2",
                    ),
                ),
            )

            assertFailsWith<Throwable> {
                driver.execute(null, "UPDATE evidence_link_history SET state='active' WHERE ledger_id='ledger-a' AND link_id='link-a'", 0)
            }
            assertFailsWith<Throwable> {
                driver.execute(null, "DELETE FROM evidence_link WHERE ledger_id='ledger-a' AND link_id='link-a'", 0)
            }
            assertFailsWith<Throwable> {
                driver.execute(null, "UPDATE evidence_projection SET target_account_id='account-x' WHERE ledger_id='ledger-a' AND projection_id='proj-evidence-a'", 0)
            }
            assertFailsWith<Throwable> {
                driver.execute(null, "DELETE FROM evidence_projection WHERE ledger_id='ledger-a'", 0)
            }
            assertFailsWith<Throwable> {
                driver.execute(null, "DELETE FROM reconciliation_correction_snapshot WHERE ledger_id='ledger-a'", 0)
            }
            // A second CURRENT row for one evidence violates the partial unique index.
            assertFailsWith<Throwable> {
                driver.execute(
                    null,
                    "INSERT INTO evidence_projection(ledger_id, projection_id, evidence_id, source_id, source_hash, target_account_id, currency_code, currency_precision, raw_amount_minor, raw_currency_precision, normalized_amount_minor, direction_token, state, rejection_code, rule_id, rule_version, materialization_request_id, materialized_at) VALUES ('ledger-a','proj-evidence-a-dupe','evidence-a','source-a','hash-a','account-bank-a','CNY',2,1000,2,1000,'out','READY',NULL,'p408_evidence_projection_v1',1,'request-dupe','2026-08-10T13:00:00+08:00')",
                    0,
                )
            }
            // The guards never consumed any row: everything is still intact.
            assertEquals(2L, count(driver, "SELECT count(*) FROM evidence_link_history WHERE link_id='link-a'"))
            assertEquals(1L, count(driver, "SELECT count(*) FROM evidence_projection"))
        } finally {
            driver.close()
        }
    }

    // ------------------------------------------------------------------ TP-12

    @Test
    fun tp12StateChainPendingCheckedMissingThenReConfirmed() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seedBase(driver)
            val store = SqlDelightP408ReconciliationStore(database, driver)
            assertIs<P408ReconciliationResult.Accepted>(store.confirmLink(confirmRequest()))
            assertEquals(
                listOf(1L to "PENDING", 2L to "CHECKED"),
                reconciliationHistory(driver, "reconciliation-posting-a"),
            )

            val correction = SqlDelightCorrectionStore(database, driver)
            assertIs<P408ReconciliationResult.Accepted>(
                correction.correct(
                    correctRequestForInvalidationOnly(
                        requestId = "correction-missing-again",
                        reason = P408CorrectionReason.CORRECTED,
                        resultState = P408CorrectionResultState.MISSING,
                        affectedPostingId = "posting-a",
                        previousLinkId = "link-a",
                        reconciliationId = "reconciliation-posting-a",
                    ),
                ),
            )
            assertEquals(
                listOf(1L to "PENDING", 2L to "CHECKED", 3L to "MISSING"),
                reconciliationHistory(driver, "reconciliation-posting-a"),
            )

            // Supplementary material arrives: a fresh explicit confirmation re-proves
            // the same evidence against the same posting (no active link remains).
            val reConfirmed =
                assertIs<P408ReconciliationResult.Accepted>(
                    store.confirmLink(
                        confirmRequest(
                            requestId = "request-reconfirm",
                            linkId = "link-d",
                            createdAt = "2026-08-12T13:00:00+08:00",
                        ),
                    ),
                )
            assertEquals(4L, reConfirmed.receipt.historySequence)
            assertEquals(
                listOf(1L to "PENDING", 2L to "CHECKED", 3L to "MISSING", 4L to "CHECKED"),
                reconciliationHistory(driver, "reconciliation-posting-a"),
            )
        } finally {
            driver.close()
        }
    }

    // ------------------------------------------------------------------ TP-13

    @Test
    fun tp13RejectedProjectionIsSupersededByReadinessReExpression() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seedBase(driver)
            val projections = SqlDelightEvidenceProjectionStore(database, driver)

            // Standalone explicit materialization with an unmatchable target: the
            // source currency is CNY and the requested target USD -> REJECTED row.
            val rejected =
                assertIs<P408MaterializeResult.Rejected>(
                    projections.materialize(
                        P408MaterializationRequest(
                            ledgerId = "ledger-a",
                            requestId = "request-rejected-projection",
                            evidenceId = "evidence-a",
                            targetAccountId = "account-bank-a",
                            targetCurrencyCode = "USD",
                            targetCurrencyPrecision = 2,
                            materializedAt = "2026-08-10T11:00:00+08:00",
                        ),
                    ),
                )
            assertEquals("P408_PROJECTION_CURRENCY_MISMATCH", rejected.code)

            // Re-expression through the correction authority: same raw facts with the
            // correct CNY target -> the REJECTED row is frozen superseded and a READY
            // current row takes over (spec §8 V-E-A / TP-13).
            val ready =
                assertIs<EnsureReadyResult.Ready>(
                    projections.ensureCurrentForCorrection(
                        P408MaterializationRequest(
                            ledgerId = "ledger-a",
                            requestId = "request-reexpress",
                            evidenceId = "evidence-a",
                            targetAccountId = "account-bank-a",
                            targetCurrencyCode = "CNY",
                            targetCurrencyPrecision = 2,
                            materializedAt = "2026-08-10T11:30:00+08:00",
                        ),
                    ),
                )
            assertEquals("proj-evidence-a-2", ready.projection.projectionId)
            assertEquals("READY", ready.projection.state.storageValue)
            assertEquals(
                1L,
                count(driver, "SELECT count(*) FROM evidence_projection WHERE projection_id='proj-evidence-a' AND state='REJECTED' AND superseded_by_projection_id='proj-evidence-a-2'"),
            )
            val current =
                database.ledgerQueries
                    .selectP408EvidenceProjection("ledger-a", "evidence-a")
                    .executeAsOne()
            assertEquals("READY", current.state)
            assertEquals("proj-evidence-a-2", current.projection_id)
        } finally {
            driver.close()
        }
    }

    // ------------------------------------------------------------------ TP-15

    @Test
    fun tp15CorrectionRequestsNeverRetireIntoTheConfirmFamily() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seedBase(driver)
            val store = SqlDelightP408ReconciliationStore(database, driver)
            assertIs<P408ReconciliationResult.Accepted>(store.confirmLink(confirmRequest()))

            // A correction request id that already belongs to a CONFIRM claim is a
            // changed retry inside the correction family -> typed, zero writes.
            val correction = SqlDelightCorrectionStore(database, driver)
            val replayAsCorrection =
                assertIs<P408ReconciliationResult.Rejected>(
                    correction.correct(
                        correctRequestForInvalidationOnly(
                            requestId = "request-a",
                            affectedPostingId = "posting-a",
                            previousLinkId = "link-a",
                            reconciliationId = "reconciliation-posting-a",
                        ),
                    ),
                )
            assertEquals("P408_CORRECTION_SNAPSHOT_MISMATCH", replayAsCorrection.code)
            // The confirm claim and its rows are untouched.
            assertEquals(1L, count(driver, "SELECT count(*) FROM reconciliation_request"))
            assertEquals(1L, count(driver, "SELECT count(*) FROM evidence_link"))
        } finally {
            driver.close()
        }
    }

    // ------------------------------------------------------------------ TP-16

    @Test
    fun tp16MatcherInputsStayStableAndOnlyTheCorrectionPortWritesResultStates() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seedBase(driver)
            val store = SqlDelightP408ReconciliationStore(database, driver)
            assertIs<P408ReconciliationResult.Accepted>(store.confirmLink(confirmRequest()))
            val correction = SqlDelightCorrectionStore(database, driver)
            val matcher = P408Matcher(windowDays = 2)

            // Same deterministic input before and after the correction produces a
            // byte-equal matcher output (SPEC-001 regression lock).
            val evidence = evidenceFactsForMatcher()
            val proposal = matcher.match(evidence, listOf(postingFactsForMatcher("posting-a", "account-bank-a")))
            assertEquals(P408MatchDisposition.PROPOSED_MATCH, proposal.disposition)
            assertEquals(proposal, matcher.match(evidence, listOf(postingFactsForMatcher("posting-a", "account-bank-a"))))

            // The matcher is pure and report renders are read-only: proposing or
            // reading never writes a P4-08 row.
            assertEquals(1L, count(driver, "SELECT count(*) FROM evidence_link"))
            assertEquals(1L, count(driver, "SELECT count(*) FROM reconciliation_request"))
            store.readReconciliationReport("ledger-a")
            assertEquals(1L, count(driver, "SELECT count(*) FROM evidence_link"))

            // A fact-identical correction re-links; the matcher proposes the
            // replacement (current) posting with an identical basis, and the
            // surface count only grew by the correction's own chain rows.
            seedReplacementVersion(driver, newOutAccountId = "account-bank-a")
            assertIs<P408ReconciliationResult.Accepted>(
                correction.correct(
                    correctRequestForSuccessor(
                        requestId = "correction-tp16",
                        previousLinkId = "link-a",
                        successorPostingId = "posting-a2",
                        accountId = "account-bank-a",
                        projectionId = "proj-evidence-a",
                        successorLinkId = "link-b",
                        reconciliationId = "reconciliation-posting-a2",
                    ),
                ),
            )
            val afterPosting = matcher.match(evidence, listOf(postingFactsForMatcher("posting-a2", "account-bank-a")))
            assertEquals(P408MatchDisposition.PROPOSED_MATCH, afterPosting.disposition)
            assertEquals(proposal.candidates.single().basis, afterPosting.candidates.single().basis)
            assertEquals(2L, count(driver, "SELECT count(*) FROM evidence_link"))

            // Only the correction port writes MISSING/DIFFERENCE result states: none
            // exist in this chain and the confirmation/report surfaces never produce them.
            assertEquals(
                0L,
                count(driver, "SELECT count(*) FROM posting_reconciliation WHERE status IN ('MISSING','DIFFERENCE')"),
            )
            assertEquals(
                P408ReconciliationStatus.CHECKED,
                store.readReconciliationReport("ledger-a").first { it.postingId == "posting-a2" }.status,
            )
        } finally {
            driver.close()
        }
    }

    // ------------------------------------------------------------------ TP-17

    @Test
    fun tp17RemarkCategoryStatisticsOnlyVersionReplacementDoesNotDisturbTheMatch() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seedBase(driver)
            val store = SqlDelightP408ReconciliationStore(database, driver)
            assertIs<P408ReconciliationResult.Accepted>(store.confirmLink(confirmRequest()))
            val balancesBefore = financialSnapshot(driver).balances

            // Fact-identical replacement version: only note/category/statistics time
            // change upstream; the five funding facts and the occurrence window are
            // byte-identical (D-048 / ACCOUNTING_RULES.md:253).
            seedReplacementVersion(driver, newOutAccountId = "account-bank-a")

            // REAL negative path: no correction is triggered for remark-level changes,
            // so no invalidate_link request, no new link, no snapshot, no balance drift.
            assertEquals(0L, count(driver, "SELECT count(*) FROM reconciliation_request WHERE operation='invalidate_link'"))
            assertEquals(1L, count(driver, "SELECT count(*) FROM evidence_link"))
            assertEquals(0L, count(driver, "SELECT count(*) FROM reconciliation_correction_snapshot"))
            assertEquals(balancesBefore, financialSnapshot(driver).balances)
            // The replacement posting carries byte-identical funding facts.
            assertEquals(
                listOf("posting-a" to 1000L, "posting-a2" to 1000L),
                selectRows(
                    driver,
                    "SELECT posting_id, abs(amount_minor) FROM posting WHERE posting_id IN ('posting-a','posting-a2') ORDER BY posting_id",
                    longColumns = listOf(false, true),
                ).map { it[0] as String to it[1] as Long },
            )
            assertEquals(
                listOf("account-bank-a", "account-bank-a"),
                selectRows(
                    driver,
                    "SELECT account_id FROM posting WHERE posting_id IN ('posting-a','posting-a2') ORDER BY posting_id",
                    longColumns = listOf(false),
                ).map { it[0] as String },
            )

            // Even when an explicit fact-identical correction is run, the funding
            // posture is net-unchanged: still CHECKED, no MISSING/DIFFERENCE, no
            // balance movement, and no new projection authority.
            val correction = SqlDelightCorrectionStore(database, driver)
            assertIs<P408ReconciliationResult.Accepted>(
                correction.correct(
                    correctRequestForSuccessor(
                        requestId = "correction-note-only",
                        previousLinkId = "link-a",
                        successorPostingId = "posting-a2",
                        accountId = "account-bank-a",
                        projectionId = "proj-evidence-a",
                        successorLinkId = "link-b",
                        reconciliationId = "reconciliation-posting-a2",
                    ),
                ),
            )
            assertEquals(
                P408ReconciliationStatus.CHECKED,
                store.readReconciliationReport("ledger-a").first { it.postingId == "posting-a2" }.status,
            )
            assertEquals(
                0L,
                count(driver, "SELECT count(*) FROM posting_reconciliation WHERE status IN ('MISSING','DIFFERENCE')"),
            )
            assertEquals(balancesBefore, financialSnapshot(driver).balances)
            assertEquals(1L, count(driver, "SELECT count(*) FROM evidence_projection"))
        } finally {
            driver.close()
        }
    }

    // ---------------------------------------------------------------- fixtures

    /** Fresh IN_MEMORY scenario: a confirmed transfer plus a fact-identical
     * replacement version, ready for exactly one correction. */
    private fun newCorrectionScenario(): CorrectionScenario {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LedgerDatabase.Schema.create(driver)
        val database = LedgerDatabase(driver)
        seedBase(driver)
        val store = SqlDelightP408ReconciliationStore(database, driver)
        assertIs<P408ReconciliationResult.Accepted>(store.confirmLink(confirmRequest()))
        seedReplacementVersion(driver, newOutAccountId = "account-bank-a")
        return CorrectionScenario(driver, SqlDelightCorrectionStore(database, driver))
    }

    private data class CorrectionScenario(
        val driver: JdbcSqliteDriver,
        val correction: SqlDelightCorrectionStore,
    )

    private fun seedBase(
        driver: JdbcSqliteDriver,
        includeSecondPosting: Boolean = true,
    ) {
        val statements =
            buildList {
                add("INSERT INTO import_request VALUES ('ledger-a','import-a','intake')")
                add("INSERT INTO import_source_record VALUES ('ledger-a','source-a','import-a','batch-a',0,'ordinary_flow_source','hash-a',1,'valid_complete',1000,'CNY',2,'2026-08-10T12:00:00+08:00','out','settled','SETTLED','legacy-settled-v1',1,'2026-08-10T12:00:00+08:00')")
                add("INSERT INTO import_evidence VALUES ('ledger-a','evidence-a','source-a','source_observation','2026-08-10T12:00:01+08:00')")
                if (includeSecondPosting) {
                    add("INSERT INTO import_request VALUES ('ledger-a','import-b','intake')")
                    add("INSERT INTO import_source_record VALUES ('ledger-a','source-b','import-b','batch-b',0,'ordinary_flow_source','hash-b',1,'valid_complete',1000,'CNY',2,'2026-08-10T12:00:00+08:00','in','settled','SETTLED','legacy-settled-v1',1,'2026-08-10T12:00:00+08:00')")
                    add("INSERT INTO import_evidence VALUES ('ledger-a','evidence-b','source-b','source_observation','2026-08-10T12:00:01+08:00')")
                }
                add("INSERT INTO ledger_transaction(transaction_id,ledger_id,kind,canonical_kind) VALUES ('tx-a','ledger-a','ACCOUNT_TRANSFER',NULL)")
                add("INSERT INTO posting_set VALUES ('posting-set-a','ledger-a')")
                add("INSERT INTO transaction_version(version_id,transaction_id,ledger_id,version_number,posting_set_id,occurred_at,statistics_at,effective_at,note) VALUES ('version-a','tx-a','ledger-a',1,'posting-set-a','2026-08-10T12:00:00+08:00','2026-08-10T12:00:00+08:00','2026-08-10T12:00:00+08:00',NULL)")
                add("INSERT INTO ledger_transaction_current_version VALUES ('tx-a','ledger-a','version-a')")
                add("INSERT INTO posting VALUES ('posting-a','posting-set-a','ledger-a',0,'account-bank-a',-1000,'CNY',2)")
                if (includeSecondPosting) {
                    add("INSERT INTO posting VALUES ('posting-b','posting-set-a','ledger-a',1,'account-platform-b',1000,'CNY',2)")
                }
                add("INSERT INTO ledger_transaction(transaction_id,ledger_id,kind,canonical_kind) VALUES ('tx-expense','ledger-a','EXPENSE',NULL)")
                add("INSERT INTO posting_set VALUES ('posting-set-expense','ledger-a')")
                add("INSERT INTO transaction_version(version_id,transaction_id,ledger_id,version_number,posting_set_id,occurred_at,statistics_at,effective_at,note) VALUES ('version-expense','tx-expense','ledger-a',1,'posting-set-expense','2026-08-10T12:00:00+08:00','2026-08-10T12:00:00+08:00','2026-08-10T12:00:00+08:00',NULL)")
                add("INSERT INTO ledger_transaction_current_version VALUES ('tx-expense','ledger-a','version-expense')")
                add("INSERT INTO posting VALUES ('posting-expense','posting-set-expense','ledger-a',0,'expense-account',1000,'CNY',2)")
            }
        statements.forEach { driver.execute(null, it, 0) }
    }

    /** Version 2 of tx-a: the corrected posting pair (amount/account may differ). */
    private fun seedReplacementVersion(
        driver: JdbcSqliteDriver,
        newOutAccountId: String,
    ) {
        val statements =
            listOf(
                "INSERT INTO posting_set VALUES ('posting-set-a2','ledger-a')",
                "INSERT INTO transaction_version(version_id,transaction_id,ledger_id,version_number,posting_set_id,occurred_at,statistics_at,effective_at,note) VALUES ('version-a2','tx-a','ledger-a',2,'posting-set-a2','2026-08-10T12:00:00+08:00','2026-08-10T12:00:00+08:00','2026-08-10T12:00:00+08:00',NULL)",
                "INSERT INTO posting VALUES ('posting-a2','posting-set-a2','ledger-a',0,'$newOutAccountId',-1000,'CNY',2)",
                "INSERT INTO posting VALUES ('posting-b2','posting-set-a2','ledger-a',1,'account-platform-b',1000,'CNY',2)",
                "UPDATE ledger_transaction_current_version SET current_version_id='version-a2' WHERE ledger_id='ledger-a' AND transaction_id='tx-a'",
            )
        statements.forEach { driver.execute(null, it, 0) }
    }

    private fun confirmRequest(
        requestId: String = "request-a",
        linkId: String = "link-a",
        createdAt: String = "2026-08-10T13:00:00+08:00",
    ) = P408ConfirmLinkRequest(
        ledgerId = "ledger-a",
        requestId = requestId,
        evidenceId = "evidence-a",
        candidateId = "candidate-transient-a",
        postingId = "posting-a",
        transactionId = "tx-a",
        amountMinor = 1000,
        currencyCode = "CNY",
        currencyPrecision = 2,
        direction = "out",
        accountId = "account-bank-a",
        responsibility = P408EvidenceResponsibility.REAL_ACCOUNT_POSTING,
        basisVersion = 2,
        matchBasis = MATCH_BASIS,
        windowDays = 2,
        naturalDayDistance = 0,
        sourceOccurredAt = "2026-08-10T12:00:00+08:00",
        confirmedAt = "2026-08-10T13:00:00+08:00",
        linkId = linkId,
        reconciliationId = "reconciliation-posting-a",
        createdAt = createdAt,
        projectionId = "proj-evidence-a",
        projectionRuleId = P408EvidenceProjectionPort.RULE_ID,
        projectionRuleVersion = 1,
        normalizedAmountMinor = 1000,
        rawAmountMinor = 1000,
        rawCurrencyPrecision = 2,
    )

    private fun correctRequestForSuccessor(
        requestId: String,
        previousLinkId: String,
        successorPostingId: String,
        accountId: String,
        projectionId: String,
        successorLinkId: String,
        reconciliationId: String,
        direction: String = "out",
        responsibility: P408EvidenceResponsibility = P408EvidenceResponsibility.REAL_ACCOUNT_POSTING,
        confirmedAt: String = "2026-08-10T14:00:00+08:00",
    ) = P408CorrectLinkRequest(
        ledgerId = "ledger-a",
        requestId = requestId,
        evidenceId = "evidence-a",
        previousLinkId = previousLinkId,
        reason = P408CorrectionReason.POSTING_REPLACED,
        affectedPostingId = successorPostingId,
        resultState = P408CorrectionResultState.CHECKED,
        successor =
            P408SuccessorLinkFacts(
                postingId = successorPostingId,
                transactionId = "tx-a",
                amountMinor = 1000,
                currencyCode = "CNY",
                currencyPrecision = 2,
                direction = direction,
                accountId = accountId,
                responsibility = responsibility,
                candidateId = "candidate-transient-a2",
                matchBasis = MATCH_BASIS,
                windowDays = 2,
                naturalDayDistance = 0,
                sourceOccurredAt = "2026-08-10T12:00:00+08:00",
            ),
        projectionId = projectionId,
        projectionRuleId = P408EvidenceProjectionPort.RULE_ID,
        projectionRuleVersion = 1,
        normalizedAmountMinor = 1000,
        rawAmountMinor = 1000,
        rawCurrencyPrecision = 2,
        confirmedAt = confirmedAt,
        successorLinkId = successorLinkId,
        successorCreatedAt = "2026-08-10T14:00:00+08:00",
        reconciliationId = reconciliationId,
    )

    private fun correctRequestForInvalidationOnly(
        requestId: String,
        affectedPostingId: String,
        previousLinkId: String,
        reconciliationId: String,
        evidenceId: String = "evidence-a",
        reason: P408CorrectionReason = P408CorrectionReason.CORRECTED,
        resultState: P408CorrectionResultState = P408CorrectionResultState.MISSING,
    ) = P408CorrectLinkRequest(
        ledgerId = "ledger-a",
        requestId = requestId,
        evidenceId = evidenceId,
        previousLinkId = previousLinkId,
        reason = reason,
        affectedPostingId = affectedPostingId,
        resultState = resultState,
        confirmedAt = "2026-08-10T14:00:00+08:00",
        reconciliationId = reconciliationId,
    )

    private fun linkHistory(
        driver: JdbcSqliteDriver,
        linkId: String,
    ): List<Triple<String, String, Long>> =
        selectRows(
            driver,
            "SELECT state, reason, sequence FROM evidence_link_history WHERE ledger_id='ledger-a' AND link_id='$linkId' ORDER BY sequence",
            longColumns = listOf(false, false, true),
        ).map { Triple(it[0] as String, it[1] as String, it[2] as Long) }

    private fun activeLinksForEvidence(
        driver: JdbcSqliteDriver,
        evidenceId: String,
    ): List<String> =
        selectRows(
            driver,
            "SELECT link.link_id FROM evidence_link link JOIN evidence_link_history history ON history.ledger_id=link.ledger_id AND history.link_id=link.link_id WHERE link.ledger_id='ledger-a' AND link.evidence_id='$evidenceId' AND history.sequence=(SELECT max(h2.sequence) FROM evidence_link_history h2 WHERE h2.ledger_id=link.ledger_id AND h2.link_id=link.link_id) AND history.state='active' ORDER BY link.link_id",
            longColumns = listOf(false),
        ).map { it[0] as String }

    private fun activeLinksForPostingResponsibility(
        driver: JdbcSqliteDriver,
        postingId: String,
    ): List<String> =
        selectRows(
            driver,
            "SELECT link.link_id FROM evidence_link link JOIN evidence_link_history history ON history.ledger_id=link.ledger_id AND history.link_id=link.link_id WHERE link.ledger_id='ledger-a' AND link.posting_id='$postingId' AND history.sequence=(SELECT max(h2.sequence) FROM evidence_link_history h2 WHERE h2.ledger_id=link.ledger_id AND h2.link_id=link.link_id) AND history.state='active' ORDER BY link.link_id",
            longColumns = listOf(false),
        ).map { it[0] as String }

    private fun reconciliationStatus(
        driver: JdbcSqliteDriver,
        postingId: String,
    ): String? =
        selectRows(
            driver,
            "SELECT status FROM posting_reconciliation WHERE ledger_id='ledger-a' AND posting_id='$postingId'",
            longColumns = listOf(false),
        ).firstOrNull()?.get(0) as String?

    private fun reconciliationHistory(
        driver: JdbcSqliteDriver,
        reconciliationId: String,
    ): List<Pair<Long, String>> =
        selectRows(
            driver,
            "SELECT sequence, status FROM posting_reconciliation_history WHERE ledger_id='ledger-a' AND reconciliation_id='$reconciliationId' ORDER BY sequence",
            longColumns = listOf(true, false),
        ).map { it[0] as Long to it[1] as String }

    private fun temporalEvidence(): P408TemporalEvidence =
        P408TemporalEvidence(
            rawText = "2026-08-10T12:00:00+08:00",
            kind = "offset_datetime",
            offsetPresent = true,
            components = P408TemporalComponents(2026, 8, 10, 12, 0, 0),
            instant = Instant.parse("2026-08-10T04:00:00Z"),
        )

    /** Matcher input assembled from the READY projection authority (TP-16). */
    private fun evidenceFactsForMatcher(): P408EvidenceFacts =
        P408EvidenceFacts(
            ledgerId = "ledger-a",
            evidenceId = "evidence-a",
            raw =
                P408RawEvidenceFacts(
                    sourceId = "source-a",
                    contentHash = "hash-a",
                    amountMinor = 1000,
                    currencyCode = "CNY",
                    currencyPrecision = 2,
                    directionToken = "out",
                ),
            normalized =
                P408NormalizedProjectionFacts(
                    projectionId = "proj-evidence-a",
                    targetAccountId = "account-bank-a",
                    currencyCode = "CNY",
                    currencyPrecision = 2,
                    normalizedAmountMinor = 1000,
                    directionToken = "out",
                    ruleId = P408EvidenceProjectionPort.RULE_ID,
                    ruleVersion = 1,
                ),
            occurredAt = temporalEvidence(),
        )

    private fun postingFactsForMatcher(
        postingId: String,
        accountId: String,
    ): P408PostingFacts =
        P408PostingFacts(
            ledgerId = "ledger-a",
            postingId = postingId,
            transactionId = "tx-a",
            transactionLedgerId = "ledger-a",
            amountMinor = -1000,
            currencyCode = "CNY",
            currencyPrecision = 2,
            direction = "out",
            accountId = accountId,
            occurredAt = temporalEvidence(),
            eligibleRealAccount = true,
            current = true,
        )

    private fun sqliteProps(): Properties =
        Properties().apply {
            setProperty("foreign_keys", "true")
            setProperty("busy_timeout", "5000")
        }

    private fun financialSnapshot(driver: JdbcSqliteDriver): FinancialSnapshot {
        val rows =
            selectRows(
                driver,
                "SELECT transaction_id, ledger_id, kind FROM ledger_transaction ORDER BY transaction_id",
                longColumns = listOf(false, false, false),
            ) +
                selectRows(
                    driver,
                    "SELECT version_id, transaction_id, ledger_id, version_number, posting_set_id FROM transaction_version ORDER BY version_id",
                    longColumns = listOf(false, false, false, true, false),
                ) +
                selectRows(
                    driver,
                    "SELECT posting_id, posting_set_id, ledger_id, posting_index, account_id, amount_minor, currency_code, currency_precision FROM posting ORDER BY posting_id",
                    longColumns = listOf(false, false, false, true, false, true, false, true),
                )
        val versions =
            selectRows(
                driver,
                "SELECT transaction_id, version_number FROM transaction_version ORDER BY transaction_id, version_number",
                longColumns = listOf(false, true),
            )
        val balances =
            selectRows(
                driver,
                "SELECT p.account_id, SUM(p.amount_minor) AS balance FROM posting p JOIN transaction_version v ON v.ledger_id=p.ledger_id AND v.posting_set_id=p.posting_set_id JOIN ledger_transaction_current_version cv ON cv.ledger_id=v.ledger_id AND cv.transaction_id=v.transaction_id AND cv.current_version_id=v.version_id GROUP BY p.account_id ORDER BY p.account_id",
                longColumns = listOf(false, true),
            ).associate { it[0] as String to it[1] as Long }
        return FinancialSnapshot(rows, versions, balances)
    }

    private data class FinancialSnapshot(
        val rows: List<List<Any?>>,
        val versions: List<List<Any?>>,
        val balances: Map<String, Long>,
    ) {
        val currentVersionCount: Int get() = versions.size
    }

    private fun selectRows(
        driver: JdbcSqliteDriver,
        sql: String,
        longColumns: List<Boolean>,
    ): List<List<Any?>> =
        driver
            .executeQuery(
                null,
                sql,
                { cursor ->
                    val rows = mutableListOf<List<Any?>>()
                    while (cursor.next().value) {
                        rows +=
                            longColumns.mapIndexed { index, isLong ->
                                if (isLong) cursor.getLong(index) else cursor.getString(index)
                            }
                    }
                    app.cash.sqldelight.db.QueryResult
                        .Value(rows.toList())
                },
                0,
            ).value

    private fun count(
        driver: JdbcSqliteDriver,
        sql: String,
    ): Long =
        driver
            .executeQuery(
                null,
                sql,
                { cursor ->
                    cursor.next()
                    app.cash.sqldelight.db.QueryResult
                        .Value(cursor.getLong(0)!!)
                },
                0,
            ).value

    private companion object {
        val MATCH_BASIS = setOf("amount", "currency", "direction", "account", "occurred_at_window")
    }
}
