package com.unifiedledger.ui

import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportFileIntakeOutcome
import com.unifiedledger.application.ImportFundingState
import com.unifiedledger.application.ImportReviewRow
import com.unifiedledger.application.ImportReviewRowsPageResult
import com.unifiedledger.application.ImportReviewRowsResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * P7-04 D-4 (B4 接治落地读有界, spec `docs/specs/2026-11-13-p7-04-b1-b4-windowed-read-design.md`
 * section 3 D-4 / section 5 V-6): the pure seam [importIntakeLandingEvent] that turns ONE bounded
 * review-list page into the pick→intake landing event.
 *
 * The host that reads the page is a `@Composable` with no JVM harness (the
 * `importGroupEnumerationFirstWindowEvents` precedent), so the page→event mapping is asserted here;
 * `P503App.runImportIntakePipeline` only calls it. The seam takes a page result and the session
 * summary and NOTHING else — no whole-list reader — so the event it produces can never carry more
 * than the page it was handed. The page bound itself (`IMPORT_REVIEW_PAGE_SIZE`) is the host call
 * site's `queryPage(ledgerId, IMPORT_REVIEW_PAGE_SIZE, null)` fact, pinned by the whole-table
 * `query(` call-site grep (V-14: six sites down to five).
 *
 * Coverage map: V-6a = [aFiftyRowPageLandsExactlyThoseFiftyRowsAndItsCursor]; V-6b =
 * [theFinalPageLandsItsNullCursor]; V-6c = [anUnavailablePageLandsTheTypedFailureAndNeverAnEmptyPage].
 * The reducer side of V-5 (the cursor is written on `Rows` and preserved on `Unavailable`) lives in
 * `P503ImportReviewReducerTest`.
 */
class P503ImportIntakeLandingHostTest {
    private val session =
        ImportIntakeSessionSummary(
            displayName = "synthetic-bill.csv",
            inputRef = "pick-handle-1",
            outcome =
                ImportIntakePipelineOutcome.Intaken(
                    // The seam never inspects the outcome; an empty pick is a legal empty Accepted.
                    ImportFileIntakeOutcome.Accepted(records = emptyList(), newCandidateIds = emptyList()),
                ),
        )

    private fun row(index: Int): ImportReviewRow =
        ImportReviewRow(
            candidateId = ImportCandidateId("candidate-$index"),
            candidateKind = "ordinary_flow",
            sourceInputRef = "pick-handle-1",
            amountMinor = 3_580L,
            currencyCode = "CNY",
            currencyPrecision = 2,
            occurredAt = "2026-03-15T02:00:00Z",
            directionToken = "expense",
            statusToken = "trade_success",
            fundingState = ImportFundingState.SETTLED,
            completeness = ImportCompleteness.VALID_COMPLETE,
            contentHash = "sha256:fixed-candidate-$index",
            candidateStatus = "pending_confirmation",
            requiresConfirmation = true,
            confidence = "high",
            duplicateStatus = null,
            paymentProfileVariant = null,
            paymentProfileAssetLegKindToken = null,
            paymentProfileCreditLegKindToken = null,
        )

    private fun page(
        count: Int,
        nextAfterCandidateId: String?,
    ): ImportReviewRowsPageResult.Page =
        ImportReviewRowsPageResult.Page(
            rows = (1..count).map { row(it) },
            nextAfterCandidateId = nextAfterCandidateId,
        )

    /**
     * V-6a: a 50-row first page lands EXACTLY those 50 rows plus the page's cursor — the landing is
     * a page, not a whole-list read (the whole-table read is what the batch removes).
     */
    @Test
    fun aFiftyRowPageLandsExactlyThoseFiftyRowsAndItsCursor() {
        val firstPage = page(count = 50, nextAfterCandidateId = "cursor-50")

        val event = importIntakeLandingEvent(session, firstPage)

        assertEquals(firstPage.rows, assertIs<ImportReviewRowsResult.Rows>(event.rows).rows)
        assertEquals(50, assertIs<ImportReviewRowsResult.Rows>(event.rows).rows.size)
        assertEquals("cursor-50", event.nextAfterCandidateId)
        assertEquals(session, event.session)
    }

    /**
     * V-6b: a page whose cursor is null is the FINAL page — the event's cursor stays null, so
     * 加载更多 has nothing left to read and the list presents as complete.
     */
    @Test
    fun theFinalPageLandsItsNullCursor() {
        val finalPage = page(count = 3, nextAfterCandidateId = null)

        val event = importIntakeLandingEvent(session, finalPage)

        assertEquals(finalPage.rows, assertIs<ImportReviewRowsResult.Rows>(event.rows).rows)
        assertNull(event.nextAfterCandidateId, "a null cursor means this was the final page")
    }

    /**
     * V-6c: a page the read could not produce lands the TYPED failure — never `Rows(emptyList())`.
     * An empty `Rows` would clear the list and the cursor and read as "no candidates"; the typed
     * `Unavailable` is what the reducer maps to keeping the previous rows AND the previous cursor
     * with the read-failure banner (F1).
     */
    @Test
    fun anUnavailablePageLandsTheTypedFailureAndNeverAnEmptyPage() {
        val event = importIntakeLandingEvent(session, ImportReviewRowsPageResult.Unavailable)

        assertSame(ImportReviewRowsResult.Unavailable, event.rows)
        assertTrue(event.rows !is ImportReviewRowsResult.Rows, "a failed read must never look like an empty page")
        assertNull(event.nextAfterCandidateId)
        assertEquals(session, event.session)
    }

    // ---- D-322 count 驱动组入口 (spec `docs/specs/2026-11-14-d322-count-driven-group-entry-design.md` D-1/D-2) ----

    /**
     * D-322 D-1 (V-1): the seam carries the caller's count into the `Page` branch — the landing
     * event transports the lease's committed-session count independently of the page branch.
     */
    @Test
    fun aPageLandingCarriesTheCallerReadCount() {
        val event = importIntakeLandingEvent(session, page(count = 50, nextAfterCandidateId = "cursor-50"), count = 3L)

        assertEquals("cursor-50", event.nextAfterCandidateId)
        assertEquals(3L, event.currentSessionDuplicateGroupCount)
        assertEquals(session, event.session)
    }

    /**
     * D-322 D-1/D-2 (V-1): the `Unavailable` branch carries the caller's count TOO — the branch
     * only governs the rows/cursor F1 retention, so a count read in the same lease still lands
     * when the page read failed. The count is DECOUPLED from the page branch: the caller's reading
     * (a value or null) travels either way.
     */
    @Test
    fun anUnavailableLandingStillCarriesTheCallerReadCount() {
        val read = importIntakeLandingEvent(session, ImportReviewRowsPageResult.Unavailable, count = 3L)

        assertSame(ImportReviewRowsResult.Unavailable, read.rows)
        assertEquals(3L, read.currentSessionDuplicateGroupCount, "the count is decoupled from the page branch")

        val notRead = importIntakeLandingEvent(session, ImportReviewRowsPageResult.Unavailable, count = null)

        assertNull(notRead.currentSessionDuplicateGroupCount, "a null is 'no reading', never a zero")
    }

    /**
     * D-322 D-1: the two-argument calls keep compiling and land the default null — the default
     * preserves every pre-D-322 construction site, the same precedent as `nextAfterCandidateId`
     * (the existing V-6a/b/c cases above already pin this for the `Page` branch's rows/cursor).
     */
    @Test
    fun theTwoArgumentCallDefaultsTheCountToNull() {
        val event = importIntakeLandingEvent(session, page(count = 3, nextAfterCandidateId = null))

        assertNull(event.currentSessionDuplicateGroupCount)
    }
}
