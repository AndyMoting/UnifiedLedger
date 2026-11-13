package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.ExecuteImportIntake
import com.unifiedledger.application.IMPORT_FUNDING_RULE_LEGACY_SETTLED
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportContentFingerprint
import com.unifiedledger.application.ImportDuplicateCandidateId
import com.unifiedledger.application.ImportDuplicateIntakeIds
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
import com.unifiedledger.application.ImportReviewReadPort
import com.unifiedledger.application.ImportReviewRow
import com.unifiedledger.application.ImportReviewRowsPage
import com.unifiedledger.application.ImportReviewRowsPageResult
import com.unifiedledger.application.ImportSourceFacts
import com.unifiedledger.application.ImportSourceId
import com.unifiedledger.application.ImportStatusHistoryId
import com.unifiedledger.application.QueryImportReviewRows
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.LedgerId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * P7-04 B1/B4 windowed read (design `docs/specs/2026-11-13-p7-04-b1-b4-windowed-read-design.md` D-5,
 * acceptance V-8): the SESSION-scoped keyset page read against the whole-list read.
 *
 * The session page read exists so the group card's member set no longer depends on how much of the
 * review list happens to be loaded (D-5). Its correctness claim is an equivalence claim, and this is
 * the hard gate for it: driving `loadImportReviewRowsForSessionPage` from `null` to a null cursor must
 * reproduce the whole-list read's slice for that session — same order, same candidate-run fold, every
 * field — on the real spine write path. A wrong session predicate would instead silently change the
 * group's membership, which is the failure mode `ACC-COUNT-01` and D-243 item 3 forbid.
 *
 * Fixture: three real pick sessions on one ledger, written through `ExecuteImportIntake` (the
 * `B6PagedSessionReadEquivalenceTest` fixture discipline), all values anonymous synthetic:
 *
 * - `session-b4-a`: 4 intakes of one tuple ⇒ 4 candidates, 6 duplicate relations (intake k carries
 *   k-1 duplicate ids, because a duplicate attaches to the NEWLY intaken source).
 * - `session-b4-b`: 3 intakes of a different tuple ⇒ 3 candidates, 3 duplicate relations.
 * - `session-b4-c`: 1 intake of a third tuple ⇒ 1 candidate, NO duplicate relation, so it is a
 *   present session with zero group members — the empty-member case that is not an empty page.
 *
 * Candidate ids sort in insertion order (`candidate-b4-a1` … `candidate-b4-c1`), so a page size of 2
 * forces real multi-page traversal of every session.
 *
 * The group-member predicate is transcribed here, not imported: `importDuplicateGroupRows`
 * (`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503ImportReviewPresentation.kt:333-340`) is
 * `classifyImportCandidate(row) == SUSPECTED_DUPLICATE_PENDING_REVIEW && row.sourceInputRef == session`,
 * and the classifier's pending branch (`:70-91`) yields that class only for
 * `candidateStatus == "pending_confirmation"` with a folded `DEFERRED` duplicate status. `app-ui` is
 * not on this module's test classpath, so the two-predicate conjunction is restated below; the page
 * read itself deliberately adds only the session half (D-5), which is why the assertions check both
 * the full session slice (the read's contract) and the group-member subset (what the card enumerates).
 *
 * CURSOR RULE (`P704B4-T-CURSOR-01`, the red CI run this file now pins): `nextAfterCandidateId` is null
 * EXACTLY when a page admitted fewer CANDIDATES than `pageSize`. It is not a function of the page's
 * projection ROW count, which is larger as soon as a candidate carries a duplicate. Session B is the
 * discriminating shape: its last window holds ONE candidate carrying two duplicate rows, i.e. three
 * projection rows against a two-candidate page size, so a row-count comparison reports a live cursor
 * there and the reader pays a third, empty page.
 */
class P704B4SessionPageReadEquivalenceTest {
    private val ledgerId = LedgerId("ledger-b4-session-page")

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

    /** The k-1 duplicate ids the k-th same-tuple intake must pre-allocate, in insertion order. */
    private fun duplicates(
        prefix: String,
        count: Int,
    ): List<ImportDuplicateIntakeIds> =
        (1..count).map { index ->
            ImportDuplicateIntakeIds(
                ImportDuplicateCandidateId("duplicate-$prefix-$index"),
                ImportStatusHistoryId("duplicate-status-$prefix-$index"),
            )
        }

    private class SessionIntakeIdSource(
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
        occurredAt: String,
    ) = ImportIntakeRequest(
        identity = ImportRequestIdentity(ledgerId, ImportRequestId(requestId)),
        inputRef = inputRef,
        recordOrdinal = recordOrdinal,
        recordKind = ImportRecordKind.ORDINARY_FLOW_SOURCE,
        facts = ImportSourceFacts(amountMinor, "CNY", 2, occurredAt, "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1),
        completeness = ImportCompleteness.VALID_COMPLETE,
        candidateGeneratedAt = "2026-11-13T08:00:00Z",
    )

    private fun seed(store: SqlDelightImportSpineStore) {
        val ids =
            SessionIntakeIdSource(
                listOf(
                    intakeIds("b4-a1", "status-b4-a1"),
                    intakeIds("b4-a2", "status-b4-a2", duplicates("b4-a2", 1)),
                    intakeIds("b4-a3", "status-b4-a3", duplicates("b4-a3", 2)),
                    intakeIds("b4-a4", "status-b4-a4", duplicates("b4-a4", 3)),
                    intakeIds("b4-b1", "status-b4-b1"),
                    intakeIds("b4-b2", "status-b4-b2", duplicates("b4-b2", 1)),
                    intakeIds("b4-b3", "status-b4-b3", duplicates("b4-b3", 2)),
                    intakeIds("b4-c1", "status-b4-c1"),
                ),
            )
        val intake = ExecuteImportIntake(store, ids, ImportContentFingerprint())
        val requests =
            listOf(
                settledRow("request-b4-a1", SESSION_A, 0, 12_00L, "2026-09-01T12:30:00+08:00"),
                settledRow("request-b4-a2", SESSION_A, 1, 12_00L, "2026-09-01T12:30:00+08:00"),
                settledRow("request-b4-a3", SESSION_A, 2, 12_00L, "2026-09-01T12:30:00+08:00"),
                settledRow("request-b4-a4", SESSION_A, 3, 12_00L, "2026-09-01T12:30:00+08:00"),
                settledRow("request-b4-b1", SESSION_B, 0, 34_00L, "2026-09-02T09:05:00+08:00"),
                settledRow("request-b4-b2", SESSION_B, 1, 34_00L, "2026-09-02T09:05:00+08:00"),
                settledRow("request-b4-b3", SESSION_B, 2, 34_00L, "2026-09-02T09:05:00+08:00"),
                settledRow("request-b4-c1", SESSION_C, 0, 56_00L, "2026-09-03T18:45:00+08:00"),
            )
        for (request in requests) {
            assertIs<ImportIntakeResult.Accepted>(intake.execute(request))
        }
    }

    /**
     * Drives the session keyset exactly as the host does: the first page passes `null`, each next page
     * resumes at the previous `nextAfterCandidateId`, and a null cursor ends the loop. A cursor that
     * does not advance fails loud instead of spinning the suite.
     */
    private fun readAllSessionPages(
        adapter: SqlDelightImportReviewReadAdapter,
        sessionInputRef: String,
        pageSize: Long,
    ): List<ImportReviewRowsPage> =
        buildList {
            var afterCandidateId: String? = null
            while (true) {
                val page =
                    adapter.loadImportReviewRowsForSessionPage(ledgerId, sessionInputRef, pageSize, afterCandidateId)
                add(page)
                val next = page.nextAfterCandidateId ?: break
                require(next != afterCandidateId) { "the session keyset cursor did not advance past $next" }
                afterCandidateId = next
            }
        }

    /**
     * Row-for-row equivalence of the V-8 hard gate. The length check comes first (a lost or duplicated
     * candidate is exactly what `ACC-COUNT-01` forbids), then each index in order; the first mismatch
     * fails with the index, both candidate ids, the two folded duplicate statuses, the evidence pair,
     * the reconciliation flags and both full rows, so a red CI run names the differing row and column
     * instead of printing two four-row lists side by side.
     */
    private fun assertSessionSliceEquivalent(
        expected: List<ImportReviewRow>,
        actual: List<ImportReviewRow>,
        label: String,
    ) {
        assertEquals(expected.size, actual.size, "$label: the page chain must land every session row")
        expected.indices.forEach { index ->
            val wanted = expected[index]
            val got = actual[index]
            if (wanted != got) {
                fail(
                    "$label: index=$index expected-candidate=${wanted.candidateId.value} " +
                        "actual-candidate=${got.candidateId.value} " +
                        "expected-duplicate-status=${wanted.duplicateStatus} " +
                        "actual-duplicate-status=${got.duplicateStatus} " +
                        "expected-evidence=${wanted.evidenceId} actual-evidence=${got.evidenceId} " +
                        "expected-reconciled=${wanted.usedForReconciliation} " +
                        "actual-reconciled=${got.usedForReconciliation} " +
                        "expected=$wanted actual=$got",
                )
            }
        }
    }

    /**
     * The group-member predicate transcription documented on the class: the session half is what the
     * page read adds, the classification half is the pending / folded-`DEFERRED` branch of the matrix.
     */
    private fun isGroupMember(
        row: ImportReviewRow,
        sessionInputRef: String,
    ): Boolean =
        row.sourceInputRef == sessionInputRef &&
            row.candidateStatus == "pending_confirmation" &&
            row.duplicateStatus == ImportDuplicateStatus.DEFERRED

    private fun groupRows(
        rows: List<ImportReviewRow>,
        sessionInputRef: String,
    ): List<ImportReviewRow> = rows.filter { row -> isGroupMember(row, sessionInputRef) }

    @Test
    fun concatenatedSessionPagesEqualTheWholeReadsSessionSlice() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seed(SqlDelightImportSpineStore(database, driver))
            val adapter = SqlDelightImportReviewReadAdapter(database)
            val whole = adapter.loadImportReviewRows(ledgerId)
            assertEquals(8, whole.size, "one folded row per candidate: 4 + 3 + 1")

            val sessionA = whole.filter { it.sourceInputRef == SESSION_A }
            val pagesA = readAllSessionPages(adapter, SESSION_A, pageSize = 2)
            assertEquals(
                listOf(2, 2, 0),
                pagesA.map { it.rows.size },
                "page size 2 over 4 session candidates: two full pages, then the empty page that ends the keyset",
            )
            assertEquals(
                listOf("candidate-b4-a2", "candidate-b4-a4", null),
                pagesA.map { it.nextAfterCandidateId },
                "the cursor is the last candidate of the page; a null cursor ends the keyset",
            )
            // The whole-row comparison IS the equivalence gate. The helper compares the two lengths
            // first and then each index in order, so a mismatch names the index, both candidate ids and
            // both full rows instead of dumping two lists.
            assertSessionSliceEquivalent(sessionA, pagesA.flatMap { it.rows }, "session A")

            val sessionB = whole.filter { it.sourceInputRef == SESSION_B }
            val pagesB = readAllSessionPages(adapter, SESSION_B, pageSize = 2)
            assertEquals(
                listOf(2, 1),
                pagesB.map { it.rows.size },
                "page size 2 over 3 session candidates: the second page carries one candidate",
            )
            assertEquals(
                listOf("candidate-b4-b2", null),
                pagesB.map { it.nextAfterCandidateId },
                "the single-candidate last window is exhausted even though its projection rows outnumber the page size",
            )
            assertSessionSliceEquivalent(sessionB, pagesB.flatMap { it.rows }, "session B")
        } finally {
            driver.close()
        }
    }

    @Test
    fun theSessionsPartitionTheWholeListSoNoCandidateIsLostOrDuplicated() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seed(SqlDelightImportSpineStore(database, driver))
            val adapter = SqlDelightImportReviewReadAdapter(database)
            val whole = adapter.loadImportReviewRows(ledgerId)

            val seen =
                listOf(SESSION_A, SESSION_B, SESSION_C).flatMap { session ->
                    readAllSessionPages(adapter, session, pageSize = 2).flatMap { it.rows }
                }

            // Disjoint + complete: every whole-list row appears exactly once across the three session
            // reads, in the whole read's order. A session predicate that over-matches (leaks another
            // session's rows) or under-matches (drops its own) breaks this in one direction each.
            assertEquals(whole, seen, "the three session slices must partition the whole list in order")
        } finally {
            driver.close()
        }
    }

    @Test
    fun theGroupMemberSubsetOfThePagesEqualsTheWholeReadsGroupRows() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seed(SqlDelightImportSpineStore(database, driver))
            val adapter = SqlDelightImportReviewReadAdapter(database)
            val whole = adapter.loadImportReviewRows(ledgerId)

            val pagesA = readAllSessionPages(adapter, SESSION_A, pageSize = 2).flatMap { it.rows }
            val expectedMembers = groupRows(whole, SESSION_A)
            // a1 is the session's first intake, so no duplicate ever attaches to it: it is a session
            // row but not a group member. a2..a4 each fold a DEFERRED duplicate.
            assertEquals(
                listOf("candidate-b4-a2", "candidate-b4-a3", "candidate-b4-a4"),
                expectedMembers.map { it.candidateId.value },
                "the group predicate must keep the three duplicate-bearing session candidates",
            )
            assertEquals(expectedMembers, groupRows(pagesA, SESSION_A), "the pages' group members must equal the whole read's group rows")
            assertEquals(4, pagesA.size, "the page read stays the session's raw slice: the non-member stays in it (D-5 keeps member filtering in the pure function)")

            // The two P8-01a fields are compared, not vacuous: the spine writes one evidence row per
            // source, so every member row carries a real anchor; this fixture creates no evidence_link,
            // so the derived reconciliation flag is the (compared) false.
            assertTrue(expectedMembers.all { it.evidenceId != null }, "the evidence anchor must be a real id on the spine fixture")
            assertTrue(expectedMembers.none { it.usedForReconciliation }, "no evidence_link exists on this fixture")

            // A present session with zero group members: the read is a real page, the membership set is
            // empty.
            val pagesC = readAllSessionPages(adapter, SESSION_C, pageSize = 2).flatMap { it.rows }
            assertEquals(listOf("candidate-b4-c1"), pagesC.map { it.candidateId.value }, "session C still has one candidate row")
            assertTrue(groupRows(pagesC, SESSION_C).isEmpty(), "session C has no duplicate relation, so zero group members")
        } finally {
            driver.close()
        }
    }

    @Test
    fun aSessionWithNoCandidateIsALegalEmptyPageNotAFailure() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seed(SqlDelightImportSpineStore(database, driver))
            val adapter = SqlDelightImportReviewReadAdapter(database)

            val empty = adapter.loadImportReviewRowsForSessionPage(ledgerId, "session-b4-none", pageSize = 2, afterCandidateId = null)
            assertTrue(empty.rows.isEmpty(), "an unknown session handle matches no candidate")
            assertNull(empty.nextAfterCandidateId, "an empty page carries no cursor: the keyset is exhausted")

            // Through the use case the same read is a Page, never Unavailable: zero candidates is a
            // legal empty member set, and the group total is a count read (D-5).
            val page =
                assertIs<ImportReviewRowsPageResult.Page>(
                    QueryImportReviewRows(adapter).querySessionPage(ledgerId, "session-b4-none", pageSize = 2, afterCandidateId = null),
                )
            assertTrue(page.rows.isEmpty(), "a zero-candidate session is an empty page, not a typed failure")
            assertNull(page.nextAfterCandidateId)
        } finally {
            driver.close()
        }
    }

    @Test
    fun aCursorPastTheLastSessionCandidateIsAnExhaustedEmptyPage() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seed(SqlDelightImportSpineStore(database, driver))
            val adapter = SqlDelightImportReviewReadAdapter(database)

            val exhausted =
                adapter.loadImportReviewRowsForSessionPage(
                    ledgerId,
                    SESSION_A,
                    pageSize = 2,
                    afterCandidateId = "candidate-b4-a4",
                )
            assertTrue(exhausted.rows.isEmpty(), "nothing sorts after the session's last candidate")
            assertNull(exhausted.nextAfterCandidateId, "an exhausted keyset ends the page chain")
        } finally {
            driver.close()
        }
    }

    /**
     * `P704B4-T-CURSOR-01`: a page's projection rows can outnumber `pageSize` while its CANDIDATE count
     * is below it — and that page is still the keyset's end. Session B's last window is exactly that
     * shape: one candidate carrying two duplicate relations, asked for as a two-candidate page. A
     * row-count comparison reports a live cursor here, and the host round (which ends on a null cursor
     * only) then pays one extra member page plus one extra review page per group.
     */
    @Test
    fun aLastWindowWithMoreProjectionRowsThanCandidatesStillEndsTheKeyset() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seed(SqlDelightImportSpineStore(database, driver))
            val adapter = SqlDelightImportReviewReadAdapter(database)
            val afterCandidateId = "candidate-b4-b2"

            // The window's raw projection, before the fold: the candidate's base row plus its two
            // duplicate relations — three rows against a page size that counts two candidates.
            val projectionRows =
                database.ledgerQueries
                    .importReviewRowsForSessionPage(
                        ledger_id = ledgerId.value,
                        session_input_ref = SESSION_B,
                        page_ledger_id = ledgerId.value,
                        after_candidate_id = afterCandidateId,
                        page_size = 2,
                    ).executeAsList()
            assertTrue(
                projectionRows.size >= 2,
                "the old rule advanced only when the projection filled a page; it held ${projectionRows.size} rows",
            )

            val page = adapter.loadImportReviewRowsForSessionPage(ledgerId, SESSION_B, pageSize = 2, afterCandidateId = afterCandidateId)
            assertEquals(1, page.rows.size, "the fold renders that window as ONE candidate row")
            assertNull(page.nextAfterCandidateId, "one candidate on a two-candidate page is the keyset's end")
        } finally {
            driver.close()
        }
    }

    @Test
    fun aFailingOrUnimplementedSessionPageReadIsUnavailableAndNeverAnEmptyPage() {
        val throwing =
            object : ImportReviewReadPort {
                override fun loadImportReviewRowsForSessionPage(
                    ledgerId: LedgerId,
                    sessionInputRef: String,
                    pageSize: Long,
                    afterCandidateId: String?,
                ): ImportReviewRowsPage = throw UnsupportedOperationException("the port cannot page by session")
            }
        assertIs<ImportReviewRowsPageResult.Unavailable>(
            QueryImportReviewRows(throwing).querySessionPage(ledgerId, SESSION_A, pageSize = 2, afterCandidateId = null),
            "a throwing port must be a typed read failure, never an empty page",
        )
        // The port's own default must be the same typed failure: a composition that never wired the
        // session page read cannot present the group as empty.
        assertIs<ImportReviewRowsPageResult.Unavailable>(
            QueryImportReviewRows(object : ImportReviewReadPort {})
                .querySessionPage(ledgerId, SESSION_A, pageSize = 2, afterCandidateId = null),
            "the port default must map to Unavailable, not to an empty page",
        )

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seed(SqlDelightImportSpineStore(database, driver))
            val adapter = SqlDelightImportReviewReadAdapter(database)
            // A non-positive page size is a programming error, not an empty group: the adapter refuses
            // it loudly and the use case still maps the refusal to Unavailable.
            assertFailsWith<IllegalArgumentException> {
                adapter.loadImportReviewRowsForSessionPage(ledgerId, SESSION_A, pageSize = 0, afterCandidateId = null)
            }
            assertIs<ImportReviewRowsPageResult.Unavailable>(
                QueryImportReviewRows(adapter).querySessionPage(ledgerId, SESSION_A, pageSize = 0, afterCandidateId = null),
            )
        } finally {
            driver.close()
        }
    }

    private companion object {
        const val SESSION_A = "session-b4-a"
        const val SESSION_B = "session-b4-b"
        const val SESSION_C = "session-b4-c"
    }
}
