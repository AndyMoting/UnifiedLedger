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
 * frame, so a band that never changed can never be read as "rendered" — and that run must END AT THE
 * NEWEST SAMPLE: a run that ended earlier is an older plateau, and pinning the reading to it would
 * report a settled state the list face has already replaced. The sampling interval plus the measured
 * submit-to-first-sample overhead ([P802V8Reading.firstSampleAfterSubmitMs]) is the reading's time
 * resolution, and the number is an UPPER BOUND.
 *
 * WHAT ACCEPTS A SETTLED STATE. A settled pixel grid is not accepted on its own: at that instant the
 * semantic tree must also show the result header at the frozen count, must not show the list face's
 * static `正在搜索…` row, and must hold a node that renders an entry amount inside the measured band,
 * while the app window keeps the bounds the band was cut from. A tree that is not there yet only
 * postpones acceptance (the loop keeps sampling); a band that holds no such node at all is a red, and
 * that red is the fail-closed guard for a region with nothing decidable in it.
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
        // The band is cut from the window ONCE, before the submit, and its bounds are recorded so the
        // accept-time gate can prove the window never moved under it.
        val window = surface.windowBounds()
        val band = p802V8ResultBand(window)
        val sampler = P802V8PixelSampler(instrumentation.uiAutomation, band)
        val baseline = checkNotNull(sampler.sample()) { "the pre-submit frame must be captured: $band" }
        log("${P802V8RenderFacts.DIAGNOSTIC_PREFIX} band=$band window=$window baselineCaptured=true")
        val submitNanos = surface.submitDispatchNanos()

        val samples = mutableListOf<IntArray>()
        val sampleNanos = mutableListOf<Long>()
        var settledIndex = -1
        var settledView: P802V8ResultView? = null
        var pendingProbe: String? = null
        var nextProbeNanos = 0L
        val deadline = SystemClock.elapsedRealtime() + P802V8RenderFacts.SETTLE_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline && samples.size < P802V8RenderFacts.MAX_SAMPLES) {
            val sample =
                checkNotNull(sampler.sample()) {
                    "sample ${samples.size} must be captured after the submit; a refused screenshot is not a reading"
                }
            samples += sample
            sampleNanos += System.nanoTime()
            // The accepted state must be the NEWEST sample: a run of equal samples that ended before it
            // is an EARLIER plateau (the loading face, typically), and accepting it would pin finalState,
            // completedIndex and renderFirstPageMs to frames the list face has already replaced while the
            // only bound on the reading (finite and positive) stayed green.
            val candidate = p802V8FirstSettledIndex(samples, P802V8RenderFacts.SETTLE_SAMPLES)?.takeIf { index -> index == samples.lastIndex }
            // A settled state that still equals the pre-submit frame is the untouched condition area,
            // not a rendered result list: it is rejected and sampling continues.
            if (candidate != null && !p802V8SameState(samples[candidate], baseline)) {
                // The tree is read HERE and nowhere else: this is the instant the reading would be
                // accepted, and a tree read inside the 50 ms sampling path would destroy the cadence.
                if (System.nanoTime() >= nextProbeNanos) {
                    nextProbeNanos = System.nanoTime() + P802V8RenderFacts.TREE_PROBE_INTERVAL_NANOS
                    val accepted = surface.confirmResultView(window, band)
                    if (accepted != null) {
                        settledIndex = candidate
                        settledView = accepted
                        break
                    }
                    pendingProbe = "the tree did not show the rendered result list yet"
                }
            }
            SystemClock.sleep(P802V8RenderFacts.SAMPLE_INTERVAL_MILLIS.toLong())
        }
        assertTrue(
            "${P802V8RenderFacts.DIAGNOSTIC_PREFIX} the result band never settled: samples=${samples.size} " +
                "settleWindow=${P802V8RenderFacts.SETTLE_SAMPLES} band=$band window=$window " +
                "lastProbe=${pendingProbe ?: "no candidate settled state"}",
            settledIndex >= 0,
        )
        // The candidate pin above is a live guard, not a comment: a settled index that is not the newest
        // sample means the accepted plateau is older than the frame the list face left behind.
        assertTrue(
            "the accepted settled state must be the NEWEST sample; an earlier plateau is not the state the reading reports",
            settledIndex == samples.lastIndex,
        )
        val view = checkNotNull(settledView) { "the accepted settled state must carry its tree facts" }
        assertEquals(
            "the settled state is only accepted once the result header renders the fixed group's manifest",
            P802V8RenderFacts.EXPECTED_TOTAL_COUNT.toLong(),
            view.renderedTotal,
        )
        assertEquals(
            "the rendered total at the accept instant must equal the port's total for the same file",
            seeded.totalCount,
            view.renderedTotal,
        )
        log(
            "${P802V8RenderFacts.DIAGNOSTIC_PREFIX} accept renderedTotal=${view.renderedTotal} " +
                "rowBounds=${view.rowBounds} band=$band window=$window",
        )
        val finalState = samples[settledIndex]
        val completedIndex = samples.indexOfFirst { sample -> p802V8SameState(sample, finalState) }
        assertTrue("the completed sample must be one of the samples", completedIndex >= 0)
        assertTrue(
            "the completed sample can never follow the settled one",
            completedIndex <= settledIndex,
        )
        assertTrue(
            "the completed sample must start the settled run: a match from before it would report a shorter reading",
            completedIndex <= settledIndex - (P802V8RenderFacts.SETTLE_SAMPLES - 1),
        )
        assertTrue(
            "the settled state must differ from the pre-submit frame (the surface really changed)",
            !p802V8SameState(finalState, baseline),
        )
        val renderFirstPageMs = (sampleNanos[completedIndex] - submitNanos) / 1_000_000.0
        // The fixed submit-to-first-sample overhead: the reading's resolution is the sampling
        // interval PLUS this, which is why the reading line carries it.
        val firstSampleAfterSubmitMs = (sampleNanos.first() - submitNanos) / 1_000_000.0
        log(
            "${P802V8RenderFacts.DIAGNOSTIC_PREFIX} terminal samples=${samples.size} settledIndex=$settledIndex " +
                "completedIndex=$completedIndex renderFirstPageMs=$renderFirstPageMs " +
                "firstSampleAfterSubmitMs=$firstSampleAfterSubmitMs",
        )

        // ---------------------------------------------------------------- L4: the validity guard
        // The tree answers one question here: does the settled surface still render the frozen count?
        // It never times anything (design section 1.4: a semantic tree is not a render endpoint), and
        // it is not what accepted the settle — [P802V8ResultSurface.confirmResultView] did that.
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
                    firstSampleAfterSubmitMs = firstSampleAfterSubmitMs,
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
