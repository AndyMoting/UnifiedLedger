package com.unifiedledger.android

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unifiedledger.application.P802_SEARCH_DEFAULT_WINDOW_DAYS
import com.unifiedledger.application.SearchCondition
import com.unifiedledger.application.SearchPage
import com.unifiedledger.application.SearchReadResult
import com.unifiedledger.application.SearchTimeWindow
import com.unifiedledger.domain.MerchantId
import com.unifiedledger.domain.TagId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * P8-02 i2b-2 V-8 device-timing case (spec section 5 V-8; rulings D7-12/D7-13).
 *
 * WHAT THIS CASE IS: one RECORDED READING of the search read port over the 61,000-TRANSACTION
 * four-item condition group the JVM leg uses, so both legs measure the same read. The sample is
 * RE-CREATED, not byte-identical (thirty rolling instants instead of two, the product's default
 * catalog), so the two numbers are comparable in shape and must never be subtracted. The timed
 * keyword is "咖啡标记" (the note arm; it resolves no catalog name, so the seven-table category
 * resolved a category name would be a different selectivity and is deliberately NOT the timed
 * condition.
 *
 * NO DURATION IS ASSERTED (D2=A / D7-13): the numbers go into ONE grep-able logcat line and the
 * first reading becomes the baseline. Every assertion here is a STRUCTURAL fact of the sample —
 * the frozen page size, the three arms' construction manifests, the schema version — so a green
 * run can never be read as "fast enough".
 *
 * OPT-IN ONLY (D7-12): the case is registered in the device inventory so the shard driver can
 * reach it, but a run that does not pass `ul.p802.timing=1` returns immediately and PASSES. It
 * must be a plain return, never an assumption failure: the shard driver treats every skipped case
 * as a failed or missing one, so an assumption would turn every ordinary pull request red.
 *
 * THE SECOND ENDPOINT IS HONESTLY NOT MEASURABLE HERE: "首屏渲染完成" is the Compose frame of the
 * search result surface, which lives in `app-ui`'s composable; this module has no Compose UI-test
 * harness (the repository forbids one) and `android-app` sees only that module's public API. The
 * reading therefore records `renderFirstPageMs=unmeasurable-no-compose-harness` instead of a
 * fabricated number.
 */
@RunWith(AndroidJUnit4::class)
class P802SearchDeviceTimingInstrumentedTest {
    private var fixture: P802SearchDeviceFixture? = null

    @After
    fun closeTheFixture() {
        fixture?.close()
        fixture = null
    }

    @Test
    fun thePortFirstPageReadingIsRecordedOnTheDevice() {
        val arguments = InstrumentationRegistry.getArguments()
        if (arguments.getString(TIMING_ARGUMENT) != TIMING_ENABLED) {
            Log.i(TAG, "$PREFIX-SKIPPED reason=$TIMING_ARGUMENT-not-$TIMING_ENABLED")
            return
        }

        val seeded = P802SearchDeviceFixture("timing")
        fixture = seeded
        seeded.build()

        val queryNow = Instant.fromEpochSeconds(System.currentTimeMillis() / 1000L)
        assertTrue(
            "every fixture instant must sit inside the default ${P802_SEARCH_DEFAULT_WINDOW_DAYS}-day window",
            seeded.windowStartInclusive >= queryNow - P802_SEARCH_DEFAULT_WINDOW_DAYS.days,
        )
        assertEquals("the schema must stay at the frozen version", 37L, seeded.schemaVersion)
        assertEquals("every version row must carry its statistics projection", 0L, seeded.missingProjectionRows)

        val port = checkNotNull(seeded.graph.querySearchPage) { "the production graph must wire the search read" }

        // The recorded reading: the fixed group's first page, port call only (catalog name loads,
        // the projection probe, the page statement and the count statement are all inside it).
        val start = System.nanoTime()
        val fixedPage = pageOf(port.query(P802SearchDeviceFacts.ledgerId, FIXED_CONDITION))
        val portFirstPageMs = (System.nanoTime() - start) / 1_000_000.0

        assertEquals("the frozen page size, as a literal", 50, fixedPage.entries.size)
        assertEquals(
            "the fixed group's total is its construction manifest",
            seeded.merchantMatchedIds.size.toLong(),
            fixedPage.totalCount,
        )
        assertEquals(
            "the fixed group's first page is the manifest's first page",
            seeded.merchantMatchedIds.take(PAGE_SIZE),
            ids(fixedPage),
        )
        assertTrue("the fixed group needs more than one page", fixedPage.hasMore)

        // The three keyword arms, each asserted against its own construction manifest.
        val notePage = pageOf(port.query(P802SearchDeviceFacts.ledgerId, SearchCondition(keyword = P802SearchDeviceFacts.NOTE_MARKER)))
        assertEquals("the note arm's total is its manifest", seeded.noteMatchedIds.size.toLong(), notePage.totalCount)
        assertEquals("the note arm's first page is the manifest's first page", seeded.noteMatchedIds.take(PAGE_SIZE), ids(notePage))

        val merchantPage =
            pageOf(port.query(P802SearchDeviceFacts.ledgerId, SearchCondition(keyword = P802SearchDeviceFacts.MERCHANT_NAMES.first())))
        assertEquals(
            "the merchant arm's total is its manifest",
            seeded.merchantMatchedIds.size.toLong(),
            merchantPage.totalCount,
        )
        assertEquals(
            "the merchant arm's first page is the manifest's first page",
            seeded.merchantMatchedIds.take(PAGE_SIZE),
            ids(merchantPage),
        )

        val categoryPage = pageOf(port.query(P802SearchDeviceFacts.ledgerId, SearchCondition(keyword = P802SearchDeviceFacts.CATEGORY_KEYWORD)))
        assertEquals("the category arm's total is the seven-table manifest", 7L, categoryPage.totalCount)
        assertEquals("all seven semantic-table rows are reachable", seeded.categoryMatchedIds, ids(categoryPage))

        val line =
            "$PREFIX fixture=${seeded.transactionCount} windowStart=${seeded.windowStartInclusive} " +
                "windowEnd=${seeded.windowEndExclusive} condition=$CONDITION_LABEL " +
                "portFirstPageMs=${String.format(Locale.ROOT, "%.1f", portFirstPageMs)} " +
                "renderFirstPageMs=$RENDER_UNMEASURABLE pageRows=${fixedPage.entries.size} " +
                "totalCount=${fixedPage.totalCount} schema=${seeded.schemaVersion}"
        // One logical line, chunked only because logcat truncates a single long message; every
        // chunk starts mid-line, so the grep-able prefix is the only anchor.
        for (chunk in line.chunked(LOGCAT_CHUNK)) {
            Log.i(TAG, chunk)
        }
    }

    private fun pageOf(result: SearchReadResult): SearchPage {
        assertTrue("the port must answer a page, got $result", result is SearchReadResult.Success)
        return (result as SearchReadResult.Success).page
    }

    private fun ids(page: SearchPage): List<String> = page.entries.map { it.transactionId.value }

    private companion object {
        const val TAG = "UnifiedLedger"
        const val PREFIX = "P802-V8DEV"
        const val TIMING_ARGUMENT = "ul.p802.timing"
        const val TIMING_ENABLED = "1"
        const val LOGCAT_CHUNK = 1_000
        const val RENDER_UNMEASURABLE = "unmeasurable-no-compose-harness"
        const val CONDITION_LABEL = "fixed-note+tag+merchant0"
        const val PAGE_SIZE = 50

        /** The JVM leg's fixed group (P802SearchScaleTest), so both legs measure the same read. */
        val FIXED_CONDITION =
            SearchCondition(
                keyword = P802SearchDeviceFacts.NOTE_MARKER,
                window = SearchTimeWindow.LastNinetyDays,
                tagIds = setOf(TagId(P802SearchDeviceFacts.TAG_ID)),
                merchantFilterId = MerchantId(P802SearchDeviceFacts.MERCHANT_ZERO_ID),
            )
    }
}
