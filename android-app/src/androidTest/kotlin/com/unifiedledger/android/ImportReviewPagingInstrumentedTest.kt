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
 * Bounded forward scrolls that bring the next-page affordance on screen. The walk parks at the page's
 * last row, and the affordance is the item after it, so it can sit just below the fold; the list
 * cannot scroll past its own end, so a bounded number of steps always lands on it.
 */
private const val AFFORDANCE_SEEK_SCROLLS = 6

/** Bounded forward scrolls before the terminal absence check: at the list's end they are no-ops. */
private const val TERMINAL_PROBE_SCROLLS = 4

/**
 * The 加载更多 page-crossing contract of the import review list, measured on the rendered list at a
 * three-page size (120 candidates at `IMPORT_REVIEW_PAGE_SIZE = 50L`, `P503App.kt:5966`).
 *
 * WHY THIS CASE EXISTS. The list renders ONE page of candidates and reaches rows 51+ only through
 * the 加载更多导入候选 affordance (`P503ImportReview.kt:645`, wired `onLoadMore = ::requestMoreImportReview`,
 * `P503App.kt:2646`, which reads ONE keyset page at the stored cursor). The 61k device traversal
 * walked page 1 and parked at that affordance, and no case in the suite ever crossed the seam, so a
 * duplicated or dropped row at a page boundary could not have been caught. This case crosses it
 * twice at a size an ordinary device shard can afford.
 *
 * WHAT IT PROVES, page by page, in the rendered list's own terms:
 *  - page 1 renders exactly one page and is the ledger's first 50 candidates in review order;
 *  - one click appends the NEXT 50: the appended rows are set-disjoint from page 1 and the union is
 *    exactly the first 100 of the expected order (not merely "more rows appeared");
 *  - a second click reaches the tail: the last page holds the remaining 20 and the list reports
 *    completion instead of re-reading the tail — the product's cursor is null once a full page is
 *    no longer returned (`SqlDelightImportReviewReadAdapter.loadImportReviewPage`), and the
 *    affordance is rendered only while that cursor is non-null
 *    (`P503ImportReviewPresentation.kt:1484`), so its absence is the terminal state and there is no
 *    affordance left to duplicate rows with.
 *
 * HOW ROWS ARE IDENTIFIED. Never by index: each row is the harness's own render signature —
 * `amount|kind-and-facts line` (`AndroidScaleUi.candidateNodes`/`signatureOrNull`), which the walk
 * collects in list order and this case compares against the fixture's expected amounts in the
 * store's keyset order. Amounts are unique per candidate by fixture construction, so the expected
 * order is checkable without reading the database at assertion time.
 *
 * SEEDING. [ImportReviewPagingFixtureSupport] (sibling of [ImportScaleDuplicateFixtureSupport],
 * whose layout resolver, catalog bootstrap, real [com.unifiedledger.application.ExecuteImportIntake]
 * path and publication discipline it reuses) builds 120 pending candidates through the real intake
 * and publishes them into generation 1 before this process opens the app. That guard is why this
 * class must not share its process with an earlier case that launched `MainActivity`: the fixture
 * refuses to replace a database another handle holds.
 *
 * A RE-SERVED PAGE IS A RED, TWO WAYS. A page read twice renders a repeated signature run, which
 * the harness's unique-overlap merge cannot resolve to one offset (it fails as an ambiguous
 * traversal window); a page that skips rows renders a window that does not overlap what was already
 * collected (it fails the same way). Either way the walk fails before returning a list, and the
 * amount-sequence, distinctness and disjointness assertions below reject any silent variant.
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
        assertEquals(
            "the seeded library must cross exactly three pages at the product page size",
            3,
            importReviewPagingPageCount(candidates),
        )
        val lastPageSize = candidates - 2 * pageSize
        val expected = fixture.amountsMinor.map(::renderedAmount)
        val ui = AndroidScaleUi(instrumentation) {}

        ui.launch()
        ui.openImportViaDrawer()
        ui.await { ui.has(LIST_TOP_MARKER) }

        // ---- page 1: exactly one page, and it is the review order's first page
        val pageOne = ui.collectTraversal(anchorAmount = fixture.amountsMinor.first(), expectedRows = pageSize)
        assertEquals("page 1 must render exactly one page of candidates", pageSize, pageOne.size)
        assertEquals(
            "page 1 must be the ledger's first page in review order, row for row",
            expected.take(pageSize),
            amountsOf(pageOne),
        )
        assertEquals("a candidate must never render twice on page 1", pageOne.size, pageOne.distinct().size)
        assertOneKindLine(pageOne)
        log("page=1 rows=${pageOne.size} first=${amountsOf(pageOne).first()} last=${amountsOf(pageOne).last()}")

        // ---- page 2: one click appends the next page, with no duplicate and no gap
        // The affordance is the render signature of a non-null cursor; reaching it also proves it is
        // on screen here, where the first page's last row ended the walk.
        ui.seek(LOAD_MORE_DESCRIPTION, maxScrolls = AFFORDANCE_SEEK_SCROLLS)
        ui.click(LOAD_MORE_DESCRIPTION)
        // The append needs no sleep: the walk below is bounded and only completes once the appended
        // page has rendered, so an append that never lands fails as an incomplete traversal.
        ui.settle()
        val firstTwoPages = ui.collectTraversal(anchorAmount = fixture.amountsMinor.first(), expectedRows = 2 * pageSize)
        assertEquals("two loaded pages must render exactly two pages", 2 * pageSize, firstTwoPages.size)
        assertEquals(
            "the loaded prefix must stay the review order's own first page",
            pageOne,
            firstTwoPages.take(pageSize),
        )
        val appendedSecondPage = firstTwoPages.drop(pageSize)
        assertEquals(
            "the click must append exactly the next page, in review order",
            expected.subList(pageSize, 2 * pageSize),
            amountsOf(appendedSecondPage),
        )
        assertTrue(
            "the appended page must not repeat any row page 1 already showed",
            appendedSecondPage.intersect(pageOne.toSet()).isEmpty(),
        )
        assertEquals(
            "the union must be exactly the expected two-page prefix, with no duplicate",
            2 * pageSize,
            firstTwoPages.distinct().size,
        )
        assertOneKindLine(firstTwoPages)
        log("page=2 rows=${firstTwoPages.size} appended=${appendedSecondPage.size} first=${amountsOf(appendedSecondPage).first()} last=${amountsOf(appendedSecondPage).last()}")

        // ---- page 3: one more click reaches the tail and leaves no affordance behind
        ui.seek(LOAD_MORE_DESCRIPTION, maxScrolls = AFFORDANCE_SEEK_SCROLLS)
        ui.click(LOAD_MORE_DESCRIPTION)
        ui.settle()
        // The walk's own top seek is bounded, and a two-page climb sits near that bound, so the
        // caller pre-seeks; the second call is a no-op once the marker is on screen.
        ui.seekBackToTop(LIST_TOP_MARKER)
        ui.seekBackToTop(LIST_TOP_MARKER)
        val everyPage = ui.collectTraversal(anchorAmount = fixture.amountsMinor.first(), expectedRows = candidates)
        assertEquals("the completed list must render every seeded candidate exactly once", candidates, everyPage.size)
        assertEquals(
            "the loaded prefix must stay the review order's own first two pages",
            firstTwoPages,
            everyPage.take(2 * pageSize),
        )
        val appendedLastPage = everyPage.drop(2 * pageSize)
        assertEquals("the last page must hold exactly the remaining rows", lastPageSize, appendedLastPage.size)
        assertEquals(
            "the last page must be exactly the remaining tail, in review order",
            expected.subList(2 * pageSize, candidates),
            amountsOf(appendedLastPage),
        )
        assertTrue(
            "the last page must not repeat any row the first two pages already showed",
            appendedLastPage.intersect(firstTwoPages.toSet()).isEmpty(),
        )
        assertEquals("the completed list must hold no duplicate row", candidates, everyPage.distinct().size)
        assertOneKindLine(everyPage)
        log("page=3 rows=${everyPage.size} appended=${appendedLastPage.size} first=${amountsOf(appendedLastPage).first()} last=${amountsOf(appendedLastPage).last()}")

        // ---- the terminal state: the cursor is null, so nothing offers another page to read
        // The walk parks on the last candidate; one more bounded forward scroll puts the list's real
        // end on screen, so an affordance that still rendered would be visible and fail here.
        repeat(TERMINAL_PROBE_SCROLLS) { ui.scroll(fraction = 0.6f) }
        assertFalse(
            "a complete list must not render the next-page affordance (a null cursor is the completion state)",
            ui.has(LOAD_MORE_DESCRIPTION),
        )
        assertFalse("a complete list must not render the page-1 加载更多 button either", ui.has(LOAD_MORE_TEXT))
        log("terminal rows=${everyPage.size} affordance=absent duplicates=0 gaps=0")
    }
}

/**
 * The rendered amount of one exact minor-unit value: `AndroidScaleUi.amountText`'s own shape (that
 * formatter is private to the harness), so the expected and observed sides are comparable strings.
 * Integer arithmetic only — an amount never passes through a floating-point type.
 */
private fun renderedAmount(amountMinor: Long): String = (amountMinor / 100).toString() + "." + (amountMinor % 100).toString().padStart(2, '0') + " CNY"

/** The amount halves of the collected `amount|kind` signatures, in collected order. */
private fun amountsOf(signatures: List<String>): List<String> = signatures.map { it.substringBefore('|') }

/**
 * The kind half of every collected signature, asserted to be ONE line carrying the ordinary-flow
 * kind and the no-duplicate marker: the rows this case pages through are all one class group, and a
 * row that stopped rendering its kind line would make the amount comparison alone too weak.
 */
private fun assertOneKindLine(signatures: List<String>) {
    val kindLines = signatures.map { it.substringAfter('|') }.distinct()
    assertEquals("every row must render one kind-and-facts line", 1, kindLines.size)
    assertTrue(
        "the row's kind line must name the ordinary-flow kind and the no-duplicate marker, got ${kindLines.single()}",
        kindLines.single().startsWith("类型 ordinary_flow") && kindLines.single().endsWith("重复 无"),
    )
}
