package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.ExecuteImportIntake
import com.unifiedledger.application.IMPORT_FUNDING_RULE_LEGACY_SETTLED
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportContentFingerprint
import com.unifiedledger.application.ImportDuplicateCandidateId
import com.unifiedledger.application.ImportDuplicateIntakeIds
import com.unifiedledger.application.ImportDuplicateReviewsForSessionMemberPage
import com.unifiedledger.application.ImportDuplicateReviewsForSessionMemberPageResult
import com.unifiedledger.application.ImportDuplicateReviewsForSessionRow
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
import com.unifiedledger.application.ImportSourceFacts
import com.unifiedledger.application.ImportSourceId
import com.unifiedledger.application.ImportStatusHistoryId
import com.unifiedledger.application.QueryImportDuplicateReviewsForSessionMemberPage
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.LedgerId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P7-04 B1/B4 windowed read (design `docs/specs/2026-11-13-p7-04-b1-b4-windowed-read-design.md` D-5,
 * acceptance V-8): the MEMBER-major page read of the session duplicate-review rows against the
 * whole-session read.
 *
 * `loadImportDuplicateReviewsForSessionMemberPage` exists so the group card's member set is driven by
 * pages of MEMBERS instead of the review list state (the design's §9 device finding: on the 20k group
 * the list state holds one page of 50 rows, so the members enumerated from it were empty —
 * `checkboxes=0`, no confirm affordance). Its correctness
 * claims are equivalence and order, and this is the hard gate for both: the concatenation of the pages
 * must equal `loadImportDuplicateReviewsForSession` ordered member-major — same rows, same fields,
 * same order — and a page must never cut a member's duplicate run.
 *
 * Fixture: three real pick sessions on one ledger, written through `ExecuteImportIntake` (the
 * `P704B4SessionPageReadEquivalenceTest` fixture discipline), all values anonymous synthetic:
 *
 * - `session-b4m-a`: 8 intakes. `a1..a4` repeat one tuple, so `a2/a3/a4` carry 1/2/3 duplicate ids
 *   (a duplicate attaches to the NEWLY intaken source) and `a1` carries none. `a5`, `a6` and `a7` are
 *   three DIFFERENT tuples, so they carry no duplicate at all — a run of three duplicate-less
 *   session candidates, longer than the page size this test drives. `a8` repeats `a7`'s tuple, so it
 *   is the fourth member. Members: `a2`, `a3`, `a4`, `a8`; 7 duplicate rows.
 * - `session-b4m-b`: 4 intakes of one tuple ⇒ 3 members and 6 duplicate rows, so a page of two is
 *   exactly full on the first page and SHORT on the second (the last-page case).
 * - `session-b4m-c`: 1 intake of its own tuple ⇒ a present session with zero duplicate relation:
 *   a legal empty page, not an absent verdict.
 *
 * The duplicate ids are deliberately NOT in member order (`duplicate-b4m-80` belongs to the LAST
 * member, `a8`), so the whole-session read is duplicate-major in a different order than the
 * member-major contract: an ORDER BY that kept duplicate order would fail the equivalence assertion
 * instead of passing it by coincidence.
 */
class P704B4MemberPageReadEquivalenceTest {
    private val ledgerId = LedgerId("ledger-b4-member-page")

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

    /** The k-1 duplicate ids the k-th same-tuple intake must pre-allocate, in any id order. */
    private fun duplicates(vararg duplicateIds: String): List<ImportDuplicateIntakeIds> =
        duplicateIds.map { duplicateId ->
            ImportDuplicateIntakeIds(
                ImportDuplicateCandidateId(duplicateId),
                ImportStatusHistoryId("status-$duplicateId"),
            )
        }

    private class MemberIntakeIdSource(
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
            MemberIntakeIdSource(
                listOf(
                    intakeIds("b4m-a1", "status-b4m-a1"),
                    intakeIds("b4m-a2", "status-b4m-a2", duplicates("duplicate-b4m-90")),
                    intakeIds("b4m-a3", "status-b4m-a3", duplicates("duplicate-b4m-91", "duplicate-b4m-92")),
                    intakeIds("b4m-a4", "status-b4m-a4", duplicates("duplicate-b4m-93", "duplicate-b4m-94", "duplicate-b4m-95")),
                    intakeIds("b4m-a5", "status-b4m-a5"),
                    intakeIds("b4m-a6", "status-b4m-a6"),
                    intakeIds("b4m-a7", "status-b4m-a7"),
                    intakeIds("b4m-a8", "status-b4m-a8", duplicates("duplicate-b4m-80")),
                    intakeIds("b4m-b1", "status-b4m-b1"),
                    intakeIds("b4m-b2", "status-b4m-b2", duplicates("duplicate-b4m-b1")),
                    intakeIds("b4m-b3", "status-b4m-b3", duplicates("duplicate-b4m-b2", "duplicate-b4m-b3")),
                    intakeIds("b4m-b4", "status-b4m-b4", duplicates("duplicate-b4m-b4", "duplicate-b4m-b5", "duplicate-b4m-b6")),
                    intakeIds("b4m-c1", "status-b4m-c1"),
                ),
            )
        val intake = ExecuteImportIntake(store, ids, ImportContentFingerprint())
        val requests =
            listOf(
                settledRow("request-b4m-a1", SESSION_A, 0, 12_00L, "2026-09-01T12:30:00+08:00"),
                settledRow("request-b4m-a2", SESSION_A, 1, 12_00L, "2026-09-01T12:30:00+08:00"),
                settledRow("request-b4m-a3", SESSION_A, 2, 12_00L, "2026-09-01T12:30:00+08:00"),
                settledRow("request-b4m-a4", SESSION_A, 3, 12_00L, "2026-09-01T12:30:00+08:00"),
                settledRow("request-b4m-a5", SESSION_A, 4, 21_00L, "2026-09-02T09:05:00+08:00"),
                settledRow("request-b4m-a6", SESSION_A, 5, 22_00L, "2026-09-02T10:05:00+08:00"),
                settledRow("request-b4m-a7", SESSION_A, 6, 34_00L, "2026-09-03T18:45:00+08:00"),
                settledRow("request-b4m-a8", SESSION_A, 7, 34_00L, "2026-09-03T18:45:00+08:00"),
                settledRow("request-b4m-b1", SESSION_B, 0, 56_00L, "2026-09-04T07:15:00+08:00"),
                settledRow("request-b4m-b2", SESSION_B, 1, 56_00L, "2026-09-04T07:15:00+08:00"),
                settledRow("request-b4m-b3", SESSION_B, 2, 56_00L, "2026-09-04T07:15:00+08:00"),
                settledRow("request-b4m-b4", SESSION_B, 3, 56_00L, "2026-09-04T07:15:00+08:00"),
                settledRow("request-b4m-c1", SESSION_C, 0, 78_00L, "2026-09-05T11:00:00+08:00"),
            )
        for (request in requests) {
            assertIs<ImportIntakeResult.Accepted>(intake.execute(request))
        }
    }

    /**
     * Drives the member keyset exactly as the host does: the first page passes `null`, each next page
     * resumes at the previous `nextAfterCandidateId` VALUE, and a null cursor ends the loop. A cursor
     * that does not advance fails loud instead of spinning the suite.
     */
    private fun readAllMemberPages(
        adapter: SqlDelightImportReviewReadAdapter,
        sessionInputRef: String,
        pageSize: Long,
    ): List<ImportDuplicateReviewsForSessionMemberPage> =
        buildList {
            var afterCandidateId: String? = null
            while (true) {
                val page =
                    adapter.loadImportDuplicateReviewsForSessionMemberPage(
                        ledgerId,
                        sessionInputRef,
                        pageSize,
                        afterCandidateId,
                    )
                add(page)
                val next = page.nextAfterCandidateId?.value ?: break
                require(next != afterCandidateId) { "the member keyset cursor did not advance past $next" }
                afterCandidateId = next
            }
        }

    /** The member-major order the whole-session read must agree with: member, then duplicate id. */
    private fun memberMajor(
        rows: List<ImportDuplicateReviewsForSessionRow>,
    ): List<ImportDuplicateReviewsForSessionRow> =
        rows.sortedWith(
            compareBy<ImportDuplicateReviewsForSessionRow>(
                { row -> row.subjectCandidateId.value },
                { row -> row.review.duplicateCandidateId.value },
            ),
        )

    private fun readWholeSession(
        adapter: SqlDelightImportReviewReadAdapter,
        sessionInputRef: String,
    ): List<ImportDuplicateReviewsForSessionRow> =
        assertNotNull(
            adapter.loadImportDuplicateReviewsForSession(ledgerId, sessionInputRef),
            "a present session must be a row list, not the absent verdict",
        )

    @Test
    fun memberPagesConcatenateToTheWholeSessionReadInMemberMajorOrder() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seed(SqlDelightImportSpineStore(database, driver))
            val adapter = SqlDelightImportReviewReadAdapter(database)

            val whole = readWholeSession(adapter, SESSION_A)
            val expected = memberMajor(whole)
            // The whole-session read is ordered by duplicate id only, and the fixture's duplicate ids
            // are not in member order, so the member-major expectation is a DIFFERENT list. Without
            // this the equivalence assertion below could be satisfied by a query that never sorts by
            // member at all.
            assertNotEquals(
                whole,
                expected,
                "the whole-session read must be duplicate-major here, or the equivalence is vacuous",
            )

            val pages = readAllMemberPages(adapter, SESSION_A, pageSize = 2)
            val pagedRows = pages.flatMap { page -> page.rows }
            // Data-class equality IS the hard gate: it compares the order, the subject candidate of
            // every row and every field of the folded review row (status, decision, reason, snapshot,
            // fingerprint, possible-existing-source facts).
            assertEquals(
                expected,
                pagedRows,
                "concatenated member pages must equal the whole-session read, member-major",
            )
            assertEquals(
                whole.map { row -> row.subjectCandidateId.value }.toSet(),
                pagedRows.map { row -> row.subjectCandidateId.value }.toSet(),
                "the pages must cover exactly the whole-session read's member set",
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun memberMajorOrderHoldsWithinAndAcrossMembersAndMatchesTheWholeListRead() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seed(SqlDelightImportSpineStore(database, driver))
            val adapter = SqlDelightImportReviewReadAdapter(database)

            val pagedRows = readAllMemberPages(adapter, SESSION_A, pageSize = 2).flatMap { page -> page.rows }
            assertEquals(
                listOf(
                    "candidate-b4m-a2",
                    "candidate-b4m-a3",
                    "candidate-b4m-a3",
                    "candidate-b4m-a4",
                    "candidate-b4m-a4",
                    "candidate-b4m-a4",
                    "candidate-b4m-a8",
                ),
                pagedRows.map { row -> row.subjectCandidateId.value },
                "members must ascend by candidate id, each carrying its whole duplicate run",
            )
            assertEquals(
                listOf(
                    "duplicate-b4m-90",
                    "duplicate-b4m-91",
                    "duplicate-b4m-92",
                    "duplicate-b4m-93",
                    "duplicate-b4m-94",
                    "duplicate-b4m-95",
                    "duplicate-b4m-80",
                ),
                pagedRows.map { row -> row.review.duplicateCandidateId.value },
                "within a member duplicate ids ascend; across members the member key wins (80 sorts last)",
            )

            // The review list's ascending candidate order, restricted to the candidates that actually
            // carry a duplicate relation, is the member order the pages walk. A read port whose
            // member predicate disagreed with the list's would break this in one direction each.
            val listMemberOrder =
                adapter
                    .loadImportReviewRows(ledgerId)
                    .filter { row -> row.sourceInputRef == SESSION_A && row.duplicateStatus != null }
                    .map { row -> row.candidateId.value }
            assertEquals(
                listMemberOrder,
                pagedRows.map { row -> row.subjectCandidateId.value }.distinct(),
                "the page member order must be the whole-list read's filtered order",
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun tinyPageSizesDriveTheWholeKeysetWithoutLossOrDuplication() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seed(SqlDelightImportSpineStore(database, driver))
            val adapter = SqlDelightImportReviewReadAdapter(database)
            val expected = memberMajor(readWholeSession(adapter, SESSION_A))

            // Page size 2 over four members: both member pages are FULL, so the cursor keeps advancing
            // and the empty page after them is the terminator (the "cursor past the end" case).
            val pagesOfTwo = readAllMemberPages(adapter, SESSION_A, pageSize = 2)
            assertEquals(
                listOf(3, 4, 0),
                pagesOfTwo.map { page -> page.rows.size },
                "two pages of two members (3 rows then 4) and the empty terminator page",
            )
            assertEquals(
                listOf("candidate-b4m-a3", "candidate-b4m-a8", null),
                pagesOfTwo.map { page -> page.nextAfterCandidateId?.value },
                "the cursor is the page's largest MEMBER id; the empty page ends the chain",
            )
            assertEquals(expected, pagesOfTwo.flatMap { page -> page.rows })

            // Page size 1: every page is exactly full, including the last member page, so the chain
            // ends on the empty page that follows it rather than on a short page.
            val pagesOfOne = readAllMemberPages(adapter, SESSION_A, pageSize = 1)
            assertEquals(
                listOf(1, 2, 3, 1, 0),
                pagesOfOne.map { page -> page.rows.size },
                "one member per page, each with its whole duplicate run",
            )
            assertEquals(
                listOf("candidate-b4m-a2", "candidate-b4m-a3", "candidate-b4m-a4", "candidate-b4m-a8", null),
                pagesOfOne.map { page -> page.nextAfterCandidateId?.value },
            )
            assertEquals(
                expected.map { row -> row.review.duplicateCandidateId.value },
                pagesOfOne.flatMap { page -> page.rows }.map { row -> row.review.duplicateCandidateId.value },
                "no member row may be lost or duplicated across a page chain",
            )

            // Session B has three members: with page size 2 the LAST page is short, so that page's
            // cursor is null and no empty terminator page is needed.
            val pagesOfB = readAllMemberPages(adapter, SESSION_B, pageSize = 2)
            assertEquals(listOf(3, 3), pagesOfB.map { page -> page.rows.size }, "two pages of 2 and 1 members")
            assertEquals(
                listOf("candidate-b4m-b3", null),
                pagesOfB.map { page -> page.nextAfterCandidateId?.value },
                "a page holding fewer members than requested is the last page",
            )
            assertEquals(memberMajor(readWholeSession(adapter, SESSION_B)), pagesOfB.flatMap { page -> page.rows })
        } finally {
            driver.close()
        }
    }

    @Test
    fun aRunOfCandidatesWithoutDuplicatesDoesNotEndTheKeysetEarly() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seed(SqlDelightImportSpineStore(database, driver))
            val adapter = SqlDelightImportReviewReadAdapter(database)

            // a5..a7 are session candidates WITHOUT a duplicate relation and the run is longer than
            // the page size. A member window cut without the query's nested EXISTS would admit a5+a6,
            // read no row for either, look short, and end the chain here — silently losing the a8
            // member (ACC-COUNT-01). The window must instead skip them and read a8.
            val page =
                adapter.loadImportDuplicateReviewsForSessionMemberPage(
                    ledgerId,
                    SESSION_A,
                    pageSize = 2,
                    afterCandidateId = "candidate-b4m-a4",
                )
            assertEquals(
                listOf("candidate-b4m-a8"),
                page.rows.map { row -> row.subjectCandidateId.value },
                "the duplicate-less run must not consume the member window",
            )
            assertEquals(
                listOf("duplicate-b4m-80"),
                page.rows.map { row -> row.review.duplicateCandidateId.value },
            )
            assertNull(page.nextAfterCandidateId, "one member on a page of two is the last page")
        } finally {
            driver.close()
        }
    }

    @Test
    fun emptyAndUnknownSessionsAndCursorsPastTheEndAreLegalEmptyPages() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seed(SqlDelightImportSpineStore(database, driver))
            val adapter = SqlDelightImportReviewReadAdapter(database)

            // Session C is a PRESENT session whose candidates carry no duplicate relation: zero
            // members, and zero members is a legal empty page, never a failure.
            val sessionC =
                adapter.loadImportDuplicateReviewsForSessionMemberPage(ledgerId, SESSION_C, pageSize = 2, afterCandidateId = null)
            assertTrue(sessionC.rows.isEmpty(), "a session with no duplicate relation has no member row")
            assertNull(sessionC.nextAfterCandidateId, "an empty page carries no cursor")

            // A handle that names no source on this ledger is the same legal empty page.
            val unknown =
                adapter.loadImportDuplicateReviewsForSessionMemberPage(ledgerId, "session-b4m-none", pageSize = 2, afterCandidateId = null)
            assertTrue(unknown.rows.isEmpty())
            assertNull(unknown.nextAfterCandidateId)

            // A cursor naming no member of this session, and a cursor past the last member, both match
            // nothing: an empty page with no cursor, never a failure.
            for (cursor in listOf("candidate-b4m-b4", "candidate-b4m-z9")) {
                val exhausted =
                    adapter.loadImportDuplicateReviewsForSessionMemberPage(ledgerId, SESSION_A, pageSize = 2, afterCandidateId = cursor)
                assertTrue(exhausted.rows.isEmpty(), "cursor $cursor names no member after it")
                assertNull(exhausted.nextAfterCandidateId, "an exhausted keyset has no next cursor")
            }

            // The same reads through the use case are Pages, never Unavailable: zero members is a legal
            // group of zero, and the group total is a count read, not this page's member count.
            val useCase = QueryImportDuplicateReviewsForSessionMemberPage(adapter)
            val firstPage =
                assertIs<ImportDuplicateReviewsForSessionMemberPageResult.Page>(
                    useCase.query(ledgerId, SESSION_A, pageSize = 2, afterCandidateId = null),
                )
            assertEquals(3, firstPage.page.rows.size)
            assertEquals(ImportCandidateId("candidate-b4m-a3"), firstPage.page.nextAfterCandidateId)
            val emptyPage =
                assertIs<ImportDuplicateReviewsForSessionMemberPageResult.Page>(
                    useCase.query(ledgerId, "session-b4m-none", pageSize = 2, afterCandidateId = null),
                )
            assertTrue(emptyPage.page.rows.isEmpty(), "the use case must not turn an empty page into Unavailable")
            assertNull(emptyPage.page.nextAfterCandidateId)
        } finally {
            driver.close()
        }
    }

    @Test
    fun aFailingOrUnimplementedMemberPageReadIsUnavailableAndNeverAnEmptyPage() {
        val throwing =
            object : ImportReviewReadPort {
                override fun loadImportDuplicateReviewsForSessionMemberPage(
                    ledgerId: LedgerId,
                    sessionInputRef: String,
                    pageSize: Long,
                    afterCandidateId: String?,
                ): ImportDuplicateReviewsForSessionMemberPage = throw IllegalStateException("injected read failure")
            }
        assertIs<ImportDuplicateReviewsForSessionMemberPageResult.Unavailable>(
            QueryImportDuplicateReviewsForSessionMemberPage(throwing).query(ledgerId, SESSION_A, pageSize = 2, afterCandidateId = null),
            "a throwing port must be a typed read failure, never an empty page",
        )
        // The port's own default must be the same typed failure: a composition that never wired the
        // member page read cannot present the group as empty.
        assertIs<ImportDuplicateReviewsForSessionMemberPageResult.Unavailable>(
            QueryImportDuplicateReviewsForSessionMemberPage(object : ImportReviewReadPort {})
                .query(ledgerId, SESSION_A, pageSize = 2, afterCandidateId = null),
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
                adapter.loadImportDuplicateReviewsForSessionMemberPage(ledgerId, SESSION_A, pageSize = 0, afterCandidateId = null)
            }
            assertIs<ImportDuplicateReviewsForSessionMemberPageResult.Unavailable>(
                QueryImportDuplicateReviewsForSessionMemberPage(adapter)
                    .query(ledgerId, SESSION_A, pageSize = 0, afterCandidateId = null),
            )
        } finally {
            driver.close()
        }
    }

    private companion object {
        const val SESSION_A = "session-b4m-a"
        const val SESSION_B = "session-b4m-b"
        const val SESSION_C = "session-b4m-c"
    }
}
