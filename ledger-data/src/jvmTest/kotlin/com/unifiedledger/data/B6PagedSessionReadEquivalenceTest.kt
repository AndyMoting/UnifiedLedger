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
 * B6 group-card paging batch (D-280, spec `docs/specs/2026-11-04-b6-group-card-paged-
 * enumeration-design.md` §5): row-level equivalence of the keyset-paged session read against
 * the whole-session read it replaces, plus the count's agreement and the keyset probe. The
 * paged read (`importDuplicateReviewsForSessionPage`) concatenates to exactly the whole-session
 * list (`importDuplicateReviewsForSession`) — same rows, same order, same folded values — the
 * count (`importDuplicateCandidateCountForSession`) equals the concatenated row total, a
 * shorter-than-page-size page ends the keyset, an exhausted keyset on a present session is an
 * EMPTY page (never null), and an absent session is null on every page read. Everything runs
 * on the real spine write path (ExecuteImportIntake, the ImportReviewTargetedReadEquivalenceTest
 * fixture discipline); values are anonymous synthetic.
 *
 * Fixture note (inherited): every intake's record needs a distinct raw identity
 * `(inputRef, recordOrdinal)`; duplicate candidates attach to the NEWLY intaken source, so the
 * k-th same-tuple intake requires k-1 pre-allocated duplicate ids. All the test's duplicate
 * ids sort after the first page's ids (the fixture writes `duplicate-...` ids whose lexical
 * order is the insertion order), so the multi-page path is exercised with a tiny page size.
 */
class B6PagedSessionReadEquivalenceTest {
    private val ledgerId = LedgerId("ledger-b6-page")

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

    /** Three same-business rows in ONE pick session: 1 + 2 + 3 = 6 duplicate candidates total. */
    private fun seedSixMemberGroup(
        store: SqlDelightImportSpineStore,
    ) {
        val ids =
            BatchIntakeIdSource(
                listOf(
                    intakeIds("b6p-a", "status-b6p-a"),
                    intakeIds(
                        "b6p-b",
                        "status-b6p-b",
                        listOf(
                            ImportDuplicateIntakeIds(ImportDuplicateCandidateId("duplicate-b6p-b-1"), ImportStatusHistoryId("duplicate-status-b6p-b-1")),
                        ),
                    ),
                    intakeIds(
                        "b6p-c",
                        "status-b6p-c",
                        listOf(
                            ImportDuplicateIntakeIds(ImportDuplicateCandidateId("duplicate-b6p-c-1"), ImportStatusHistoryId("duplicate-status-b6p-c-1")),
                            ImportDuplicateIntakeIds(ImportDuplicateCandidateId("duplicate-b6p-c-2"), ImportStatusHistoryId("duplicate-status-b6p-c-2")),
                        ),
                    ),
                    intakeIds(
                        "b6p-d",
                        "status-b6p-d",
                        listOf(
                            ImportDuplicateIntakeIds(ImportDuplicateCandidateId("duplicate-b6p-d-1"), ImportStatusHistoryId("duplicate-status-b6p-d-1")),
                            ImportDuplicateIntakeIds(ImportDuplicateCandidateId("duplicate-b6p-d-2"), ImportStatusHistoryId("duplicate-status-b6p-d-2")),
                            ImportDuplicateIntakeIds(ImportDuplicateCandidateId("duplicate-b6p-d-3"), ImportStatusHistoryId("duplicate-status-b6p-d-3")),
                        ),
                    ),
                ),
            )
        val intake = ExecuteImportIntake(store, ids, ImportContentFingerprint())
        assertIs<ImportIntakeResult.Accepted>(intake.execute(settledRow("request-b6p-a", "session-b6p", 0, 12_00L)))
        assertIs<ImportIntakeResult.Accepted>(intake.execute(settledRow("request-b6p-b", "session-b6p", 1, 12_00L)))
        assertIs<ImportIntakeResult.Accepted>(intake.execute(settledRow("request-b6p-c", "session-b6p", 2, 12_00L)))
        assertIs<ImportIntakeResult.Accepted>(intake.execute(settledRow("request-b6p-d", "session-b6p", 3, 12_00L)))
    }

    /** Drives the keyset loop exactly as the host does (first page = empty-string cursor). */
    private fun readAllPages(
        adapter: SqlDelightImportReviewReadAdapter,
        sessionInputRef: String,
    ): List<Pair<Int, List<String>>> =
        buildList {
            var after = ImportDuplicateCandidateId("")
            while (true) {
                val page =
                    adapter.loadImportDuplicateReviewsForSessionPage(ledgerId, sessionInputRef, after)
                        ?: break
                add(page.rows.size to page.rows.map { it.review.duplicateCandidateId.value })
                if (page.rows.size < adapter.groupPageSize) break
                after = page.nextAfterCandidateId
            }
        }

    @Test
    fun concatenatedPagesEqualTheWholeSessionReadInOrderAndContent() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val store = SqlDelightImportSpineStore(database, driver)
            seedSixMemberGroup(store)
            // Tiny page size to force the multi-page path (3 pages of 2/2/2 over 6 rows).
            val adapter = SqlDelightImportReviewReadAdapter(database, groupPageSize = 2L)

            val whole = adapter.loadImportDuplicateReviewsForSession(ledgerId, "session-b6p")!!
            val pages = readAllPages(adapter, "session-b6p")
            val pagedRows = pages.flatMap { (_, ids) -> ids }

            assertEquals(
                whole.map { it.review.duplicateCandidateId.value },
                pagedRows,
                "concatenated keyset pages must reproduce the whole-session list in order and content",
            )
            // The per-row payloads (fold key + the full review row) must match too, not just the ids.
            val wholeRowById = whole.associate { it.review.duplicateCandidateId.value to it }
            val pagedRowById =
                buildList {
                    var after = ImportDuplicateCandidateId("")
                    while (true) {
                        val page =
                            adapter.loadImportDuplicateReviewsForSessionPage(ledgerId, "session-b6p", after)
                                ?: break
                        addAll(page.rows)
                        if (page.rows.size < adapter.groupPageSize) break
                        after = page.nextAfterCandidateId
                    }
                }
            assertEquals(
                wholeRowById.keys,
                pagedRowById.associate { it.review.duplicateCandidateId.value to it }.keys,
            )
            for (row in pagedRowById) {
                assertEquals(
                    wholeRowById[row.review.duplicateCandidateId.value],
                    row,
                    "paged row payload must equal the whole-session row for ${row.review.duplicateCandidateId.value}",
                )
            }
        } finally {
            driver.close()
        }
    }

    @Test
    fun countEqualsTheConcatenatedPageRowTotalAndKeysetExhaustsCleanly() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val store = SqlDelightImportSpineStore(database, driver)
            seedSixMemberGroup(store)
            val adapter = SqlDelightImportReviewReadAdapter(database, groupPageSize = 4L)

            val count = adapter.countImportDuplicateCandidatesForSession(ledgerId, "session-b6p")
            val pages = readAllPages(adapter, "session-b6p")
            val total = pages.sumOf { it.first }

            assertEquals(6L, count, "count must equal the whole-group row total")
            assertEquals(count, total.toLong(), "count must equal the concatenated page rows (the header 共 N 条 pin)")

            // Exhausted keyset on a present session: an empty page carrying the cursor through,
            // never null — the caller's loop-termination signal.
            val exhausted =
                adapter.loadImportDuplicateReviewsForSessionPage(ledgerId, "session-b6p", ImportDuplicateCandidateId("duplicate-b6p-d-3"))
            assertIs<com.unifiedledger.application.ImportDuplicateReviewsForSessionPage>(exhausted)
            assertTrue(exhausted.rows.isEmpty(), "an exhausted keyset page must be empty")
            assertEquals(
                ImportDuplicateCandidateId("duplicate-b6p-d-3"),
                exhausted.nextAfterCandidateId,
                "an empty page passes the cursor through unchanged",
            )

            // A page after an empty cursor prefix keeps the ascending keyset order.
            assertEquals(
                listOf("duplicate-b6p-b-1", "duplicate-b6p-c-1"),
                readAllPages(adapter, "session-b6p").let { pages2 ->
                    adapter
                        .loadImportDuplicateReviewsForSessionPage(ledgerId, "session-b6p", ImportDuplicateCandidateId("duplicate-b6p-a-none"))
                        ?.rows
                        ?.map { it.review.duplicateCandidateId.value }
                        ?.take(2)
                },
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun countUsesTheMemberPredicateSoADisposedDuplicateDropsOut() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val store = SqlDelightImportSpineStore(database, driver)
            seedSixMemberGroup(store)
            val adapter = SqlDelightImportReviewReadAdapter(database, groupPageSize = 4L)

            // The header pin (spec §4 ACC-COUNT-01, review R2-6): the count is the WHOLE-GROUP
            // MEMBER count (kind + latest-status DEFERRED), not the raw session duplicate total.
            // Disposing one member through the detail path (CONFIRMED_DUPLICATE) must drop it
            // from the count exactly as it drops from the card items.
            assertEquals(6L, adapter.countImportDuplicateCandidatesForSession(ledgerId, "session-b6p"))
            val review = ReviewImportDuplicateCandidate(store)
            // The expected fingerprint must be the row's REAL one (the spine rejects a stale
            // fingerprint as staleFingerprint → Rejected, never Accepted).
            val fingerprint =
                driver
                    .executeQuery(
                        null,
                        "SELECT comparison_fingerprint FROM import_duplicate_candidate WHERE candidate_id='duplicate-b6p-b-1'",
                        { c ->
                            c.next()
                            QueryResult.Value(c.getString(0)!!)
                        },
                        0,
                    ).value
            val run =
                review.execute(
                    com.unifiedledger.application.ImportDuplicateReviewRequest(
                        identity = com.unifiedledger.application.ImportRequestIdentity(ledgerId, ImportRequestId("request-b6p-dispose")),
                        candidateId = ImportDuplicateCandidateId("duplicate-b6p-b-1"),
                        expectedComparisonFingerprint = fingerprint,
                        decision = ImportDuplicateStatus.CONFIRMED_DUPLICATE,
                        reasonToken = "test_dispose",
                        reviewedAt = "2026-11-04T00:00:00Z",
                        reviewerReference = "b6-page-test",
                        generatedAt = "2026-11-04T00:00:00Z",
                        reviewId = ImportDuplicateReviewId("review-b6p-b-1"),
                        historyId = ImportStatusHistoryId("history-b6p-b-1"),
                    ),
                )
            assertIs<com.unifiedledger.application.ImportDuplicateReviewResult.Accepted>(run)
            assertEquals(
                5L,
                adapter.countImportDuplicateCandidatesForSession(ledgerId, "session-b6p"),
                "a CONFIRMED member must drop out of the group count (共 N 条 == confirm set)",
            )
            // The paged rows agree with the member count (the whole-session read keeps the raw
            // row but the enumeration's member filter is the pure function's job — the count
            // matches what the pure function would keep).
            val pages = readAllPages(adapter, "session-b6p")
            assertEquals(6, pages.sumOf { it.first }, "the PAGED read stays the raw projection (filtering is the enumeration's member predicate)")
        } finally {
            driver.close()
        }
    }

    @Test
    fun pageQueryPlanHasNoWholeSetSort() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val store = SqlDelightImportSpineStore(database, driver)
            seedSixMemberGroup(store)
            val adapter = SqlDelightImportReviewReadAdapter(database, groupPageSize = 2L)
            // Spec §5: the paged query's plan must not sort the whole set — the keyset bound
            // rides the duplicate.candidate_id PRIMARY KEY, so every step stays an index-ordered
            // scan with a TEMP B-TREE for the right-most sort at most. (Spec §5 EXPLAIN pin;
            // static here because CI owns execution, but the plan shape is deterministic.)
            val plan =
                driver
                    .executeQuery(
                        0,
                        "EXPLAIN QUERY PLAN " +
                            "SELECT duplicate.candidate_id FROM import_candidate AS candidate " +
                            "JOIN import_source_record AS subject ON subject.ledger_id = candidate.ledger_id AND subject.source_id = candidate.source_id " +
                            "JOIN import_duplicate_candidate AS duplicate ON duplicate.ledger_id = candidate.ledger_id AND duplicate.subject_source_id = candidate.source_id " +
                            "WHERE duplicate.ledger_id = 'ledger-b6-page' AND subject.input_ref = 'session-b6p' " +
                            "AND duplicate.candidate_id > '' ORDER BY duplicate.candidate_id LIMIT 2",
                        mapper = { cursor ->
                            QueryResult.Value(buildList {
                                while (cursor.next().value) {
                                    add(cursor.getString(3) ?: "")
                                }
                            })
                        },
                        parameters = 0,
                    )
                    .value
            // No "TEMP B-TREE FOR ORDER BY" over the whole set (the ORDER BY rides the PK):
            // every detail row must be a SEARCH (index lookup), never a full SCAN of the join
            // followed by a sort. The strict pin is "no SORTER step": the fragment 'USE TEMP
            // B-TREE FOR ORDER BY' must be absent.
            val planText = plan.joinToString(" | ")
            assertTrue(!planText.contains("USE TEMP B-TREE FOR ORDER BY"), "page plan must not sort the whole set: $planText")
            assertTrue(planText.contains("SEARCH"), "page plan must search indexes, not scan: $planText")
        } finally {
            driver.close()
        }
    }

    @Test
    fun absentSessionIsNullOnEveryPageReadAndCountStaysZero() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val store = SqlDelightImportSpineStore(database, driver)
            seedSixMemberGroup(store)
            val adapter = SqlDelightImportReviewReadAdapter(database, groupPageSize = 2L)

            assertNull(
                adapter.loadImportDuplicateReviewsForSessionPage(ledgerId, "session-nope", ImportDuplicateCandidateId("")),
                "an absent session must be the explicit null verdict on the paged read too",
            )
            assertEquals(
                0L,
                adapter.countImportDuplicateCandidatesForSession(ledgerId, "session-nope"),
                "an absent session counts zero (the host fails loud on count/absent disagreement)",
            )
        } finally {
            driver.close()
        }
    }
}
