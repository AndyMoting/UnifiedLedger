package com.unifiedledger.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.ExecuteImportIntake
import com.unifiedledger.application.IMPORT_FUNDING_RULE_LEGACY_SETTLED
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportContentFingerprint
import com.unifiedledger.application.ImportDuplicateCandidateId
import com.unifiedledger.application.ImportDuplicateIntakeIds
import com.unifiedledger.application.ImportDuplicateReviewId
import com.unifiedledger.application.ImportDuplicateReviewRequest
import com.unifiedledger.application.ImportDuplicateReviewResult
import com.unifiedledger.application.ImportDuplicateStatus
import com.unifiedledger.application.ImportEvidenceId
import com.unifiedledger.application.ImportFundingState
import com.unifiedledger.application.ImportIntakeIdSource
import com.unifiedledger.application.ImportIntakeIds
import com.unifiedledger.application.ImportIntakeRequest
import com.unifiedledger.application.ImportIntakeResult
import com.unifiedledger.application.ImportRecordKind
import com.unifiedledger.application.ImportRequestId
import com.unifiedledger.application.ImportRequestIdentity
import com.unifiedledger.application.ImportReviewRow
import com.unifiedledger.application.ImportSourceFacts
import com.unifiedledger.application.ImportSourceId
import com.unifiedledger.application.ImportStatusHistoryId
import com.unifiedledger.application.ReviewImportDuplicateCandidate
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.LedgerId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A-PERF (P7-04 read-governance batch, spec section 5): row-level equivalence of the targeted
 * reads against the whole-ledger read + client filter they replace. The detail projection
 * (`importReviewRowForCandidate`, the import_candidate PK target) must fold to exactly the row
 * the previous `importReviewRowsForLedger` + `candidate_id` filter produced — including the
 * blocking-first duplicate fold across multiple duplicate candidates and the absent verdict for
 * an unknown candidate; the two existence probes (`importCandidateExistsByCandidateId` /
 * `importCandidateExistsByInputRef`) must answer the exact absent-vs-empty verdicts the port
 * contract freezes (ImportReviewReadPort). Everything runs on the real spine write path
 * (ExecuteImportIntake + ReviewImportDuplicateCandidate, the ImportSpineLifecycleEndToEndTest
 * fixture discipline); values are anonymous synthetic.
 *
 * Fixture note (the spine's duplicate semantics, ImportSpineLifecycleEndToEndTest p407 vectors):
 * every intake's record needs a distinct raw identity `(inputRef, recordOrdinal)` — records of
 * ONE pick session share the inputRef and differ by ordinal, records of different sessions use
 * different inputRefs. A duplicate candidate attaches to the NEWLY intaken source as its subject
 * and stores ONE determinate representative (the minimum earlier matching source id), so every
 * same-tuple intake demands exactly one duplicate id. The read-side fold over SEVERAL relations
 * is therefore built by appending one more relation directly
 * ([insertSecondDuplicateRelation]); no product write path produces such a row.
 */
class ImportReviewTargetedReadEquivalenceTest {
    private val ledgerId = LedgerId("ledger-aperf")

    private fun intakeIds(
        prefix: String,
        statusId: String,
        duplicateIds: List<ImportDuplicateIntakeIds> = emptyList(),
    ) = ImportIntakeIds(
        sourceId = ImportSourceId("source-$prefix"),
        evidenceId = ImportEvidenceId("evidence-$prefix"),
        candidateId = ImportCandidateId("candidate-$prefix"),
        statusHistoryId = ImportStatusHistoryId(statusId),
        duplicateIds = duplicateIds,
    )

    private class BatchIntakeIdSource(
        private val batches: List<ImportIntakeIds>,
    ) : ImportIntakeIdSource {
        val calls = AtomicInteger(0)

        override fun next(requiredDuplicateIds: Int): ImportIntakeIds {
            val index = calls.getAndIncrement()
            require(index < batches.size) { "intake id batch exhausted" }
            return batches[index]
        }
    }

    private fun settledRow(
        requestId: String,
        inputRef: String,
        recordOrdinal: Int,
        amountMinor: Long,
    ) = ImportIntakeRequest(
        identity = ImportRequestIdentity(ledgerId, ImportRequestId(requestId)),
        inputRef = inputRef,
        recordOrdinal = recordOrdinal,
        recordKind = ImportRecordKind.ORDINARY_FLOW_SOURCE,
        facts = ImportSourceFacts(amountMinor, "CNY", 2, "2026-09-01T12:30:00+08:00", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1),
        completeness = ImportCompleteness.VALID_COMPLETE,
        candidateGeneratedAt = "2026-09-17T08:00:00Z",
    )

    /**
     * The whole-ledger baseline: the list read folded per candidate (the exact adapter shape of
     * loadImportReviewRows) filtered to one candidate — the pre-batch detail derivation.
     */
    private fun wholeLedgerDetailBaseline(
        adapter: SqlDelightImportReviewReadAdapter,
        candidateId: ImportCandidateId,
    ): ImportReviewRow? = adapter.loadImportReviewRows(ledgerId).firstOrNull { it.candidateId == candidateId }

    /**
     * Fixture-only second relation for a subject candidate. The representative rule lets the
     * spine write exactly one relation per settled intake (the minimum matching source id), so a
     * read-side fold over several relations can only be built by appending this row directly. It
     * keeps the schema's own shape: an `EXACT_BUSINESS_TUPLE` candidate owned by the subject
     * intake's request, plus its `DEFERRED` creation history row, so every append-only guard and
     * the history-sequence trigger see the same shape the store writes.
     */
    private fun insertSecondDuplicateRelation(
        driver: JdbcSqliteDriver,
        subjectSourceId: String,
        possibleExistingSourceId: String,
        duplicateCandidateId: String,
        duplicateStatusHistoryId: String,
        ownerRequestId: String,
    ) {
        driver.execute(
            null,
            """
            INSERT INTO import_duplicate_candidate(
              ledger_id, candidate_id, subject_source_id, possible_existing_source_id, kind,
              comparison_fingerprint, comparison_snapshot, provenance, confidence, rule_id,
              rule_version, generated_at, creation_request_id
            ) VALUES (
              '${ledgerId.value}', '$duplicateCandidateId', '$subjectSourceId', '$possibleExistingSourceId',
              'EXACT_BUSINESS_TUPLE', 'sha256:fixture-$duplicateCandidateId', '{}',
              'source_declared + mechanical_decode + p407_exact_business_tuple_v1', 'exact',
              'p407_exact_business_tuple_v1', 1, '2026-09-17T08:00:00Z', '$ownerRequestId'
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            INSERT INTO import_duplicate_status_history(ledger_id, candidate_id, sequence, history_id, status, request_id, operation_class)
            VALUES ('${ledgerId.value}', '$duplicateCandidateId', 1, '$duplicateStatusHistoryId', 'DEFERRED', '$ownerRequestId', 'creation')
            """.trimIndent(),
            0,
        )
    }

    @Test
    fun detailTargetedReadEqualsTheWholeLedgerFilterFoldPerRow() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val store = SqlDelightImportSpineStore(database, driver)
            // Three same-business rows from three distinct pick sessions -> each settled intake
            // stores its single representative relation (b -> a, c -> a). Candidate-c's second
            // relation (c -> b) is appended as a fixture: no intake can write two rows any more.
            val ids =
                BatchIntakeIdSource(
                    listOf(
                        intakeIds("aperf-a", "status-aperf-a"),
                        intakeIds(
                            "aperf-b",
                            "status-aperf-b",
                            listOf(ImportDuplicateIntakeIds(ImportDuplicateCandidateId("duplicate-aperf-b"), ImportStatusHistoryId("duplicate-status-aperf-b"))),
                        ),
                        intakeIds(
                            "aperf-c",
                            "status-aperf-c",
                            listOf(ImportDuplicateIntakeIds(ImportDuplicateCandidateId("duplicate-aperf-c-a"), ImportStatusHistoryId("duplicate-status-aperf-c-a"))),
                        ),
                    ),
                )
            val intake = ExecuteImportIntake(store, ids, ImportContentFingerprint())
            assertIs<ImportIntakeResult.Accepted>(intake.execute(settledRow("request-aperf-a", "batch-aperf-a", 0, 12_00L)))
            assertIs<ImportIntakeResult.Accepted>(intake.execute(settledRow("request-aperf-b", "batch-aperf-b", 0, 12_00L)))
            assertIs<ImportIntakeResult.Accepted>(intake.execute(settledRow("request-aperf-c", "batch-aperf-c", 0, 12_00L)))
            insertSecondDuplicateRelation(
                driver = driver,
                subjectSourceId = "source-aperf-c",
                possibleExistingSourceId = "source-aperf-b",
                duplicateCandidateId = "duplicate-aperf-c-b",
                duplicateStatusHistoryId = "duplicate-status-aperf-c-b",
                ownerRequestId = "request-aperf-c",
            )
            val adapter = SqlDelightImportReviewReadAdapter(database)

            // Every candidate's targeted detail row equals the whole-ledger baseline row
            // (candidate fields, folded duplicate verdict, payment profile columns — the full
            // ImportReviewRow equality). Candidate-a carries no duplicate (null verdict), b one,
            // c two.
            for (candidate in listOf("candidate-aperf-a", "candidate-aperf-b", "candidate-aperf-c")) {
                val id = ImportCandidateId(candidate)
                val targeted = adapter.loadImportCandidateDetail(ledgerId, id)
                val baseline = wholeLedgerDetailBaseline(adapter, id)
                assertTrue(targeted != null && baseline != null, "candidate $candidate must exist on both reads")
                assertEquals(baseline, targeted!!.row, "targeted detail row must equal the whole-ledger filter fold for $candidate")
                assertEquals(
                    database.ledgerQueries.selectImportCandidateLatestSequence(ledgerId.value, candidate).executeAsOne(),
                    targeted.statusHistoryCount,
                )
            }

            // An unknown candidate reads as exactly zero rows -> the explicit absent verdict
            // (null detail, never a fabricated empty row).
            assertNull(adapter.loadImportCandidateDetail(ledgerId, ImportCandidateId("candidate-does-not-exist")))
        } finally {
            driver.close()
        }
    }

    @Test
    fun duplicateFoldBlockingFirstMatchesAcrossTargetedAndWholeLedgerReads() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val store = SqlDelightImportSpineStore(database, driver)
            val ids =
                BatchIntakeIdSource(
                    listOf(
                        intakeIds("aperf-d", "status-aperf-d"),
                        intakeIds(
                            "aperf-e",
                            "status-aperf-e",
                            listOf(ImportDuplicateIntakeIds(ImportDuplicateCandidateId("duplicate-aperf-e"), ImportStatusHistoryId("duplicate-status-aperf-e"))),
                        ),
                        intakeIds(
                            "aperf-f",
                            "status-aperf-f",
                            listOf(ImportDuplicateIntakeIds(ImportDuplicateCandidateId("duplicate-aperf-f-a"), ImportStatusHistoryId("duplicate-status-aperf-f-a"))),
                        ),
                    ),
                )
            val intake = ExecuteImportIntake(store, ids, ImportContentFingerprint())
            assertIs<ImportIntakeResult.Accepted>(intake.execute(settledRow("request-aperf-d", "batch-aperf-d", 0, 5_500L)))
            assertIs<ImportIntakeResult.Accepted>(intake.execute(settledRow("request-aperf-e", "batch-aperf-e", 0, 5_500L)))
            assertIs<ImportIntakeResult.Accepted>(intake.execute(settledRow("request-aperf-f", "batch-aperf-f", 0, 5_500L)))
            // Candidate-f's second relation (f -> e), fixture-only: the intake writes one
            // representative row (f -> d, the minimum matching source id).
            insertSecondDuplicateRelation(
                driver = driver,
                subjectSourceId = "source-aperf-f",
                possibleExistingSourceId = "source-aperf-e",
                duplicateCandidateId = "duplicate-aperf-f-b",
                duplicateStatusHistoryId = "duplicate-status-aperf-f-b",
                ownerRequestId = "request-aperf-f",
            )
            val adapter = SqlDelightImportReviewReadAdapter(database)

            // The subject candidate of two DEFERRED duplicates: the fold reads DEFERRED on BOTH
            // reads before any review.
            val subject = ImportCandidateId("candidate-aperf-f")
            assertEquals(
                ImportDuplicateStatus.DEFERRED,
                wholeLedgerDetailBaseline(adapter, subject)!!.duplicateStatus,
            )
            assertEquals(
                ImportDuplicateStatus.DEFERRED,
                adapter.loadImportCandidateDetail(ledgerId, subject)!!.row.duplicateStatus,
            )

            // Review ONE of the subject's duplicates to CONFIRMED_DUPLICATE — the blocking
            // verdict must win the fold on both reads even though the other duplicate stays
            // DEFERRED.
            val fingerprint =
                driver
                    .executeQuery(
                        null,
                        "SELECT comparison_fingerprint FROM import_duplicate_candidate WHERE candidate_id='duplicate-aperf-f-a'",
                        { c ->
                            c.next()
                            QueryResult.Value(c.getString(0)!!)
                        },
                        0,
                    ).value
            assertIs<ImportDuplicateReviewResult.Accepted>(
                ReviewImportDuplicateCandidate(store).execute(
                    ImportDuplicateReviewRequest(
                        identity = ImportRequestIdentity(ledgerId, ImportRequestId("review-aperf-f")),
                        candidateId = ImportDuplicateCandidateId("duplicate-aperf-f-a"),
                        expectedComparisonFingerprint = fingerprint,
                        decision = ImportDuplicateStatus.CONFIRMED_DUPLICATE,
                        reasonToken = "duplicate",
                        reviewedAt = "2026-09-17T10:00:00+08:00",
                        reviewerReference = "aperf-test",
                        generatedAt = "2026-09-17T10:00:00+08:00",
                        reviewId = ImportDuplicateReviewId("review-aperf-f"),
                        historyId = ImportStatusHistoryId("review-history-aperf-f"),
                    ),
                ),
            )
            assertEquals(
                ImportDuplicateStatus.CONFIRMED_DUPLICATE,
                wholeLedgerDetailBaseline(adapter, subject)!!.duplicateStatus,
            )
            assertEquals(
                ImportDuplicateStatus.CONFIRMED_DUPLICATE,
                adapter.loadImportCandidateDetail(ledgerId, subject)!!.row.duplicateStatus,
            )

            // The single-duplicate subject keeps its DEFERRED fold on both reads (unreviewed).
            assertEquals(
                ImportDuplicateStatus.DEFERRED,
                wholeLedgerDetailBaseline(adapter, ImportCandidateId("candidate-aperf-e"))!!.duplicateStatus,
            )
            assertEquals(
                ImportDuplicateStatus.DEFERRED,
                adapter.loadImportCandidateDetail(ledgerId, ImportCandidateId("candidate-aperf-e"))!!.row.duplicateStatus,
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun existenceProbesAnswerTheAbsentVsEmptyVerdictsTyped() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val store = SqlDelightImportSpineStore(database, driver)
            val ids =
                BatchIntakeIdSource(
                    listOf(
                        intakeIds("aperf-g", "status-aperf-g"),
                        intakeIds(
                            "aperf-h",
                            "status-aperf-h",
                            listOf(ImportDuplicateIntakeIds(ImportDuplicateCandidateId("duplicate-aperf-h"), ImportStatusHistoryId("duplicate-status-aperf-h"))),
                        ),
                    ),
                )
            val intake = ExecuteImportIntake(store, ids, ImportContentFingerprint())
            // ONE pick session, TWO records: the shared inputRef is the session handle
            // (R-Q09-1), the ordinal distinguishes the records; the same business tuple mints
            // the second record's duplicate against the first.
            assertIs<ImportIntakeResult.Accepted>(intake.execute(settledRow("request-aperf-g", "batch-aperf-g", 0, 8_800L)))
            assertIs<ImportIntakeResult.Accepted>(intake.execute(settledRow("request-aperf-h", "batch-aperf-g", 1, 8_800L)))
            val adapter = SqlDelightImportReviewReadAdapter(database)

            // Present candidate WITHOUT duplicates -> the genuine empty comparison set (not
            // absent); the PK probe must not report a present candidate as absent.
            assertEquals(emptyList(), adapter.loadImportDuplicateReviews(ledgerId, ImportCandidateId("candidate-aperf-g")))
            // Present candidate WITH a duplicate -> the duplicate set reads non-empty.
            val withDuplicates = adapter.loadImportDuplicateReviews(ledgerId, ImportCandidateId("candidate-aperf-h"))
            assertEquals(1, withDuplicates!!.size)
            // Unknown candidate -> the explicit absent verdict (null, distinct from empty).
            assertNull(adapter.loadImportDuplicateReviews(ledgerId, ImportCandidateId("candidate-does-not-exist")))

            // The session probe: the session's only duplicate lands on its second record's
            // candidate; an unknown session handle reads absent (null, G6).
            val sessionRows = adapter.loadImportDuplicateReviewsForSession(ledgerId, "batch-aperf-g")
            assertTrue(sessionRows != null, "the session with candidates must read as present, never absent")
            assertEquals(1, sessionRows.size)
            assertEquals(ImportCandidateId("candidate-aperf-h"), sessionRows.single().subjectCandidateId)
            assertNull(adapter.loadImportDuplicateReviewsForSession(ledgerId, "batch-no-such-session"))
        } finally {
            driver.close()
        }
    }
}
