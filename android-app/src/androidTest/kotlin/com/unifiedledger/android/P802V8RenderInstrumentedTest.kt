package com.unifiedledger.android

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P8-02 V-8, the 「首屏渲染完成」 endpoint, re-taken on the fixed candidate (P8-07 Q5; stage-acceptance
 * design section 3.3, the vector is the P8-02 spec's section 5 V-8 together with that spec's section 9
 * registration that this endpoint was NOT obtained on the first round).
 *
 * WHAT THIS CASE IS. One device run of the frozen V-8 read, with the SECOND endpoint actually
 * measured. It seeds the app's own stable storage with the 61,000-transaction sample
 * ([P802V8RenderSeed]), launches the app, places the frozen four-item condition group through the real
 * search surface (keyword 「咖啡标记」, the default 90-day window untouched, `tag-00`, `merchant-0`),
 * submits, and then samples the RESULT REGION in process with `UiAutomation.takeScreenshot()`.
 *
 * THE TERMINAL. Completion is the FIRST sample whose cropped, downscaled result-region grid equals the
 * FINAL settled grid, minus the submit action's dispatch instant. The final grid is the first run of
 * [P802V8RenderFacts.SETTLE_SAMPLES] mutually equal samples that also differs from the pre-submit
 * frame, so a band that never changed can never be read as "rendered". The sampling interval is the
 * reading's time resolution, and the number is an UPPER BOUND.
 *
 * WHAT IS DELIBERATELY NOT THE TERMINAL: the search port's return time (the first-round baseline, and
 * `D-311` item 2 keeps it as a different endpoint), the accessibility tree's visibility (used only to
 * VALIDATE that the measured region is the result list and that it renders the frozen count), and
 * `gfxinfo`/framestats (the `D-166` family's unconvertible values on the software renderer; the P8-02
 * spec section 9 records why that channel could not be used).
 *
 * NO THRESHOLD AND NO ACCEPTANCE. Nothing here judges whether the reading is fast, and nothing here is
 * a stage-acceptance result: the reading line is recorded, and the stage decision keeps its own record.
 *
 * THE OTHER FACTS ARE STRUCTURAL, NOT TIMED. The sample's size, the frozen page size and the fixed
 * group's construction manifest are asserted from the port on the same file before the app opens it;
 * they are the bounds that make a green run mean "the right question was asked", never "fast enough".
 */
@RunWith(AndroidJUnit4::class)
class P802V8RenderInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext

    @Test
    fun theFirstResultPageRendersAndIsMeasuredInProcessPixels() {
        val log: (String) -> Unit = { line -> Log.i(TAG, line) }

        // ---------------------------------------------------------------- L1: the sample
        val seeded = P802V8RenderSeed.prepare(targetContext, log)
        assertEquals(
            "the sample must hold the vector's ${P802V8RenderFacts.EXPECTED_TRANSACTIONS} transactions",
            P802V8RenderFacts.EXPECTED_TRANSACTIONS.toLong(),
            seeded.transactionCount,
        )
        assertEquals(
            "the first page must be the frozen page size",
            P802V8RenderFacts.EXPECTED_PAGE_ROWS,
            seeded.pageRows,
        )
        assertEquals(
            "the fixed group's total must be its construction manifest",
            P802V8RenderFacts.EXPECTED_TOTAL_COUNT.toLong(),
            seeded.totalCount,
        )
        assertTrue("the fixed group must need more than one page", seeded.hasMore)
        val apkSha256 = p802V8ApkSha256(targetContext)
        log(
            "${P802V8RenderFacts.DIAGNOSTIC_PREFIX} sample transactions=${seeded.transactionCount} " +
                "pageRows=${seeded.pageRows} totalCount=${seeded.totalCount} noteArm=${seeded.noteArmMatches} " +
                "schema=${seeded.schemaVersion} window=[${seeded.windowStartInclusive},${seeded.windowEndExclusive}) " +
                "apk=$apkSha256",
        )

        // ---------------------------------------------------------------- L2: launch + the frozen group
        val surface = P802V8ResultSurface(instrumentation, log)
        surface.launchAndOpenSearch()
        surface.applyFixedCondition()
        surface.prepareSubmit()

        // ---------------------------------------------------------------- L3: the pixel terminal
        val band = p802V8ResultBand(surface.windowBounds())
        val sampler = P802V8PixelSampler(instrumentation.uiAutomation, band)
        val baseline = checkNotNull(sampler.sample()) { "the pre-submit frame must be captured: $band" }
        log("${P802V8RenderFacts.DIAGNOSTIC_PREFIX} band=$band baselineCaptured=true")
        val submitNanos = surface.submitDispatchNanos()

        val samples = mutableListOf<IntArray>()
        val sampleNanos = mutableListOf<Long>()
        var settledIndex = -1
        val deadline = SystemClock.elapsedRealtime() + P802V8RenderFacts.SETTLE_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline && samples.size < P802V8RenderFacts.MAX_SAMPLES) {
            val sample =
                checkNotNull(sampler.sample()) {
                    "sample ${samples.size} must be captured after the submit; a refused screenshot is not a reading"
                }
            samples += sample
            sampleNanos += System.nanoTime()
            val candidate = p802V8FirstSettledIndex(samples, P802V8RenderFacts.SETTLE_SAMPLES)
            // A settled state that still equals the pre-submit frame is the untouched condition area,
            // not a rendered result list: it is rejected and sampling continues.
            if (candidate != null && !p802V8SameState(samples[candidate], baseline)) {
                settledIndex = candidate
                break
            }
            SystemClock.sleep(P802V8RenderFacts.SAMPLE_INTERVAL_MILLIS.toLong())
        }
        assertTrue(
            "${P802V8RenderFacts.DIAGNOSTIC_PREFIX} the result band never settled: samples=${samples.size} " +
                "settleWindow=${P802V8RenderFacts.SETTLE_SAMPLES} band=$band",
            settledIndex >= 0,
        )
        val finalState = samples[settledIndex]
        val completedIndex = samples.indexOfFirst { sample -> p802V8SameState(sample, finalState) }
        assertTrue("the completed sample must be one of the samples", completedIndex >= 0)
        assertTrue(
            "the completed sample can never follow the settled one",
            completedIndex <= settledIndex,
        )
        assertTrue(
            "the settled state must differ from the pre-submit frame (the surface really changed)",
            !p802V8SameState(finalState, baseline),
        )
        val renderFirstPageMs = (sampleNanos[completedIndex] - submitNanos) / 1_000_000.0
        log(
            "${P802V8RenderFacts.DIAGNOSTIC_PREFIX} terminal samples=${samples.size} settledIndex=$settledIndex " +
                "completedIndex=$completedIndex renderFirstPageMs=$renderFirstPageMs",
        )

        // ---------------------------------------------------------------- L4: the validity guard
        // The tree answers one question only: is the settled region the RESULT LIST, and does it render
        // the frozen count? It never times anything (design section 1.4: a semantic tree is not a render
        // endpoint).
        val renderedTotal =
            checkNotNull(surface.renderedTotal()) {
                "the result surface must render its header after the settle; the pixel reading needs the list face"
            }
        assertEquals(
            "the rendered count must be the fixed group's construction manifest",
            P802V8RenderFacts.EXPECTED_TOTAL_COUNT.toLong(),
            renderedTotal,
        )
        assertEquals(
            "the rendered count must equal the port's total for the same file",
            seeded.totalCount,
            renderedTotal,
        )

        // ---------------------------------------------------------------- L5: the one reading line
        val line =
            p802V8EmitReadingLine(
                log,
                P802V8Reading(
                    fixtureTransactions = seeded.transactionCount,
                    windowStartInclusive = seeded.windowStartInclusive,
                    windowEndExclusive = seeded.windowEndExclusive,
                    sampleIntervalMillis = P802V8RenderFacts.SAMPLE_INTERVAL_MILLIS,
                    samples = samples.size,
                    settleSamples = P802V8RenderFacts.SETTLE_SAMPLES,
                    renderFirstPageMs = renderFirstPageMs,
                    pageRows = seeded.pageRows,
                    totalCount = seeded.totalCount,
                    schemaVersion = seeded.schemaVersion,
                    apkSha256 = apkSha256,
                ),
            )
        // The reading is the only line carrying the reading prefix; this diagnostic proves the same
        // facts are on the device without repeating the prefix.
        log("${P802V8RenderFacts.DIAGNOSTIC_PREFIX} readingChars=${line.length}")
    }

    private companion object {
        /** The logcat tag the whole instrumented suite writes under. */
        const val TAG = "UnifiedLedger"
    }
}
