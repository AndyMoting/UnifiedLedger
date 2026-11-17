package com.unifiedledger.android

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** One logcat tag per case, so a shard's evidence can grep this case's readings out of the stream. */
private const val TAG = "ULImportReviewPaging"

/** The next-page affordance's own accessibility description (`P503ImportReview.kt:645`). */
private const val LOAD_MORE_DESCRIPTION = "加载更多导入候选"

/** The affordance's visible text (`P503ImportReview.kt:646`): absent too once the list is complete. */
private const val LOAD_MORE_TEXT = "加载更多"

/** The review list's first render item (`P503ImportReviewPresentation.kt:1016`), visible only at the top. */
private const val LIST_TOP_MARKER = "刷新清单"

/**
 * Bounded forward scrolls that bring the next-page affordance on screen for the completion click.
 * The walk parks on the last row of the page it just collected, and the affordance is the item after
 * it, so it can sit just below the fold; the list cannot scroll past its own end, so a bounded number
 * of steps always lands on it.
 */
private const val AFFORDANCE_SEEK_SCROLLS = 6

/**
 * The bound on the completion read's visible effect. The click issues one asynchronous keyset read
 * (`requestMoreImportReview` → `queryPage(cursor)` → `AppendImportReviewPage`), so the affordance only
 * disappears once that result lands and the cursor is null; `settle()` settles the frame, not the
 * read. The bound matches the harness's own page-load wait (`AndroidScaleUi` `PAGE_LOAD_TIMEOUT_MS`,
 * 60s), so a legitimate page read on this 150-row library cannot outlast it.
 */
private const val COMPLETION_WAIT_MILLIS = 60_000L

/**
 * The review list's read-failure banner (`P503ImportReviewPresentation.importReviewNoticeText`,
 * `ImportReviewNotice.ReviewReadFailed`). Diagnostic only, read when the completion read's affordance
 * lingers: a failed append keeps the cursor by design (`P503Reducer.reduceAppendImportReviewPage`'s
 * `Unavailable` arm), so this banner separates "the read failed" from "the read returned nothing and
 * the cursor was not cleared".
 */
private const val REVIEW_READ_FAILED_TEXT = "导入清单读取失败；以下保留上一次成功加载的清单，可重新刷新。"

/**
 * The 加载更多 page-crossing contract of the import review list, measured on the rendered list at a
 * three-page size (150 candidates at `IMPORT_REVIEW_PAGE_SIZE = 50L`, `P503App.kt:5966`).
 *
 * WHY THIS CASE EXISTS. The list renders ONE page of candidates and reaches rows 51+ only through
 * the 加载更多导入候选 affordance (`P503ImportReview.kt:645`, wired `onLoadMore = ::requestMoreImportReview`,
 * `P503App.kt:2646`, which reads ONE keyset page at the stored cursor). The 61k device traversal
 * walked page 1 and parked at that affordance, and no case in the suite ever crossed the seam, so a
 * duplicated or dropped row at a page boundary could not have been caught. This case crosses two
 * page boundaries and then reads the completion state.
 *
 * HOW THE WALK READS THE SEAM (D-349). `AndroidScaleUi.collectTraversal` takes a whole-page target
 * and, at every page boundary it reaches, proves the covered prefix against the caller's expected
 * rows and then clicks the product's own affordance (`loadNextPage` → `requestMoreImportReview` →
 * one keyset page read). The test therefore does not click between walks: the walk owns the page
 * transitions, and [expectedPrefix] is the fixture's own expected row signatures, so a duplicated or
 * dropped row fails at the page where it happened. The library is 150 rows — three whole pages —
 * because a target that leaves a short last page cannot be walked at all
 * (`check(targetRows % IMPORT_REVIEW_PAGE_ROWS == 0)`), and a walked tail must end on a page boundary
 * to be provable. `traversalPages`/`traversalPageClicks` record the walk's own page count and clicks.
 *
 * WHAT IT PROVES, page by page, in the rendered list's own terms:
 *  - the walk covers exactly the seeded library: three pages, two next-page clicks, every row once
 *    and in review order;
 *  - page 1 holds exactly the ledger's first 50 candidates; page 2 exactly the next 50; page 3
 *    exactly the remaining 50, each asserted against the expected slice and set-disjoint from the
 *    pages already shown (no duplicate, no gap — not merely "more rows appeared");
 *  - the completion read appends nothing and reports completion honestly: after page 3 the cursor is
 *    still non-null (a full page was returned), so one more click is offered; that click's read
 *    returns no row and a null cursor (`SqlDelightImportReviewReadAdapter.loadImportReviewPage`), so
 *    the affordance disappears (`P503ImportReviewPresentation.kt:1484`) and no row was appended after
 *    the last expected one — there is no affordance left to duplicate rows with. The disappearance is
 *    AWAITED, not asserted inline: the click starts an asynchronous read, so the test waits up to
 *    [COMPLETION_WAIT_MILLIS] for the affordance and counts the polls, and its failure text reports
 *    whether the list grew (the read served another page — a pagination defect) or stayed the same
 *    (the affordance lingers with nothing left to serve — a product finding, not a race).
 *
 * HOW ROWS ARE IDENTIFIED. Never by index: each row is the harness's own render signature —
 * `amount|kind-and-facts line` (`AndroidScaleUi.candidateNodes`/`signatureOrNull`), built here from
 * the fixture's expected amounts plus the one kind line the fixture's facts render
 * ([ImportReviewPagingFixture.kindLine]). Amounts are unique per candidate by fixture construction.
 *
 * SEEDING. [ImportReviewPagingFixtureSupport] (sibling of [ImportScaleDuplicateFixtureSupport],
 * whose layout resolver, catalog bootstrap, real [com.unifiedledger.application.ExecuteImportIntake]
 * path and publication discipline it reuses) builds the library through the real intake and
 * publishes it into generation 1 before this process opens the app. That guard is why this class must
 * not share its process with an earlier case that launched `MainActivity`: the fixture refuses to
 * replace a database another handle holds.
 *
 * Registered in `tools/ci/android-instrumented-inventory.txt` and
 * `tools/ci/android-instrumented-shards.txt`; the ordinary device shard executes it.
 */
@RunWith(AndroidJUnit4::class)
class ImportReviewPagingInstrumentedTest {
    @Test
    fun theLoadMoreAffordanceAppendsEachPageExactlyOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val log: (String) -> Unit = { line -> Log.i(TAG, line) }
        val fixture = ImportReviewPagingFixtureSupport().seed(instrumentation.targetContext, log)
        val pageSize = IMPORT_REVIEW_PAGING_PAGE_SIZE
        val candidates = fixture.candidateCount
        val pages = importReviewPagingPageCount(candidates)
        assertEquals("the seeded library must cross exactly three pages at the product page size", 3, pages)
        assertEquals("the prefix walk takes whole pages", candidates, pages * pageSize)
        val expectedAmounts = fixture.amountsMinor.map(::renderedAmount)
        val expectedSignatures = expectedAmounts.map { amount -> "$amount|${fixture.kindLine}" }
        val ui = AndroidScaleUi(instrumentation) {}

        ui.launch()
        ui.openImportViaDrawer()
        ui.await { ui.has(LIST_TOP_MARKER) }

        // ---- one prefix walk: it clicks the product's affordance at each page boundary and proves
        // the covered prefix there, so a duplicated or dropped row fails at the seam it happened on.
        val walked =
            ui.collectTraversal(
                targetRows = candidates,
                expectedPrefix = { rows -> expectedSignatures.take(rows) },
            )
        assertEquals("the walk must cover every seeded candidate", candidates, walked.size)
        assertEquals("no seeded candidate may render twice", candidates, walked.distinct().size)
        assertEquals("the walk must return the review order's own row signatures", expectedSignatures, walked)
        assertEquals("the walk must have covered three pages", 3, ui.traversalPages)
        assertEquals("three pages take exactly two next-page clicks", 2, ui.traversalPageClicks)
        assertOneKindLine(walked)

        // ---- the page boundaries, restated in this test's own terms
        val pageOne = walked.take(pageSize)
        val pageTwo = walked.subList(pageSize, 2 * pageSize)
        val pageThree = walked.subList(2 * pageSize, candidates)
        assertEquals(
            "page 1 must be the ledger's first page in review order, row for row",
            expectedSignatures.take(pageSize),
            pageOne,
        )
        assertEquals(
            "the first click must append exactly the next page, in review order",
            expectedSignatures.subList(pageSize, 2 * pageSize),
            pageTwo,
        )
        assertEquals(
            "the last page must hold exactly the remaining rows, in review order",
            expectedSignatures.subList(2 * pageSize, candidates),
            pageThree,
        )
        assertTrue(
            "the appended page must not repeat any row page 1 already showed",
            pageTwo.intersect(pageOne.toSet()).isEmpty(),
        )
        assertTrue(
            "the last page must not repeat any row the first two pages already showed",
            pageThree.intersect(walked.take(2 * pageSize).toSet()).isEmpty(),
        )
        log("pages=1..3 sizes=${pageOne.size}/${pageTwo.size}/${pageThree.size} clicks=${ui.traversalPageClicks} rows=${walked.size}")

        // ---- the terminal state: one completion read appends nothing and closes the cursor.
        // The click issues an asynchronous read, so its effect is awaited (bounded) instead of being
        // asserted in the same breath; the failure text separates "the read served another page" from
        // "the affordance lingers with nothing left to serve".
        val itemsBeforeCompletion = renderedItemCount(ui)
        ui.seek(LOAD_MORE_DESCRIPTION, maxScrolls = AFFORDANCE_SEEK_SCROLLS)
        ui.click(LOAD_MORE_DESCRIPTION)
        ui.settle()
        assertTrue(
            "the completion read must leave the tail row in place, got no ${expectedAmounts.last()}",
            ui.has(expectedAmounts.last()),
        )
        var completionPolls = 0
        val affordanceGone =
            runCatching {
                ui.await(COMPLETION_WAIT_MILLIS, onPoll = { completionPolls++ }) { !ui.has(LOAD_MORE_DESCRIPTION) }
            }.isSuccess
        val itemsAfterCompletion = renderedItemCount(ui)
        val affordancePresent = ui.has(LOAD_MORE_DESCRIPTION)
        // Diagnostic only, and only when the affordance lingers: the banner sits at the list top, so
        // the parked viewport cannot see it without walking back.
        val readFailureBanner =
            if (affordanceGone && !affordancePresent) {
                null
            } else {
                ui.seekBackToTop(LIST_TOP_MARKER)
                ui.has(REVIEW_READ_FAILED_TEXT)
            }
        val completionVerdict =
            when {
                affordanceGone && !affordancePresent ->
                    "the affordance disappeared within the wait after $completionPolls poll(s), so an inline assertion had raced the asynchronous read: no product finding"
                readFailureBanner == true ->
                    "the completion read raised the review read-failure banner, so the product kept the cursor by design: a read failure, not a pagination defect"
                itemsBeforeCompletion == null || itemsAfterCompletion == null ->
                    "the item count is not observable ($itemsBeforeCompletion -> $itemsAfterCompletion), so growth is undetermined"
                itemsAfterCompletion > itemsBeforeCompletion ->
                    "the list GREW across the completion read ($itemsBeforeCompletion -> $itemsAfterCompletion), so the read served another page: a pagination defect, not a race"
                else ->
                    "the list did not grow ($itemsBeforeCompletion -> $itemsAfterCompletion) and no failure banner is present, so the affordance lingers with nothing left to serve: a product finding, not a race"
            }
        log(
            "terminal rows=${walked.size} affordancePresent=$affordancePresent affordanceGone=$affordanceGone " +
                "items=$itemsBeforeCompletion..$itemsAfterCompletion polls=$completionPolls readFailureBanner=$readFailureBanner " +
                "waitMillis=$COMPLETION_WAIT_MILLIS verdict=$completionVerdict",
        )
        assertTrue(
            "the next-page affordance must disappear within $COMPLETION_WAIT_MILLIS ms of the completion read " +
                "(a null cursor is the completion state); observed affordancePresent=$affordancePresent, " +
                "affordanceGone=$affordanceGone, polls=$completionPolls (one poll is the immediate read, so " +
                "polls > 1 means the disappearance lagged the frame), itemsBefore=$itemsBeforeCompletion, " +
                "itemsAfter=$itemsAfterCompletion, readFailureBanner=$readFailureBanner, verdict=$completionVerdict",
            affordanceGone && !affordancePresent,
        )
        assertFalse("a complete list must not render the page-1 加载更多 button either", ui.has(LOAD_MORE_TEXT))
    }
}

/** The review list's own item count, or null when the client tree does not expose it (evidence only). */
private fun renderedItemCount(ui: AndroidScaleUi): Int? = runCatching { ui.scrollable().collectionInfo?.rowCount }.getOrNull()

/**
 * The rendered amount of one exact minor-unit value: `AndroidScaleUi.amountText`'s own shape (that
 * formatter is private to the harness), so the expected and observed sides are comparable strings.
 * Integer arithmetic only — an amount never passes through a floating-point type.
 */
private fun renderedAmount(amountMinor: Long): String = (amountMinor / 100).toString() + "." + (amountMinor % 100).toString().padStart(2, '0') + " CNY"

/**
 * The kind half of every collected signature, asserted to be ONE line carrying the ordinary-flow
 * kind and the no-duplicate marker: the rows this case pages through are all one class group, and a
 * row that stopped rendering its kind line would weaken the signature comparison.
 */
private fun assertOneKindLine(signatures: List<String>) {
    val kindLines = signatures.map { it.substringAfter('|') }.distinct()
    assertEquals("every row must render one kind-and-facts line", 1, kindLines.size)
    assertTrue(
        "the row's kind line must name the ordinary-flow kind and the no-duplicate marker, got ${kindLines.single()}",
        kindLines.single().startsWith("类型 ordinary_flow") && kindLines.single().endsWith("重复 无"),
    )
}
