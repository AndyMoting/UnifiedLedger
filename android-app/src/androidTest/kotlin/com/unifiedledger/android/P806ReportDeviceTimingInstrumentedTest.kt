package com.unifiedledger.android

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unifiedledger.application.P806CalendarRequest
import com.unifiedledger.application.P806DrilldownRequest
import com.unifiedledger.application.P806Period
import com.unifiedledger.application.P806PeriodKind
import com.unifiedledger.application.P806ReportRequest
import com.unifiedledger.application.P806SnapshotResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P8-06 batch 6 V-14 device-timing case (spec section 4 V-14; D-347: recorded readings, no
 * thresholds).
 *
 * WHAT THIS CASE IS: four SEPARATELY ATTRIBUTABLE readings of the production composite reads over
 * the anonymous 50,000-transaction single-month ledger the fixture builds on the device:
 *
 *  1. `periodAmountMs` — the `p806OrdinaryPostingTotals` statement alone (`AS positive_minor`),
 *     the SQL that produces the report's period amounts;
 *  2. `calendarBucketsMs` — the whole calendar snapshot read (version/probe/axis plus one amount
 *     statement and one count statement per natural day);
 *  3. `firstPageMs` — the drilldown PAGE statement alone (`WITH page_ids AS`), i.e. the 50 rows;
 *  4. `countMs` — the drilldown COUNT statement alone (the distinct transaction count over
 *     `catalog_category`).
 *
 * Each reading carries its real statement count and the millisecond unit, and the metric names are
 * asserted to be complete, unique and parseable, so a dispatch gate can require all four. The test
 * also pins each measured read's exact statement count and the sample's exact aggregate minor units
 * and per-day counts through the production port. NO DURATION IS ASSERTED: every assertion here is
 * a structural fact of the sample, so a green run can never be read as "fast enough".
 *
 * OPT-IN ONLY (the P8-02 V-8 precedent): the case is registered in the device inventory so the
 * shard driver reaches it, but a run without `ul.p806.timing=1` returns immediately and PASSES. It
 * must be a plain return, never an assumption failure: the shard driver treats a skipped case as a
 * failure, so an assumption would turn every ordinary pull request red.
 */
@RunWith(AndroidJUnit4::class)
class P806ReportDeviceTimingInstrumentedTest {
    private var fixture: P806ReportDeviceTimingFixture? = null

    @After
    fun closeTheFixture() {
        fixture?.close()
        fixture = null
    }

    @Test
    fun theFourCompositeReadMetricsAreRecordedOnTheDevice() {
        val arguments = InstrumentationRegistry.getArguments()
        if (arguments.getString(TIMING_ARGUMENT) != TIMING_ENABLED) {
            Log.i(TAG, "$PREFIX-SKIPPED reason=$TIMING_ARGUMENT-not-$TIMING_ENABLED")
            return
        }

        val seeded = P806ReportDeviceTimingFixture("timing")
        fixture = seeded
        seeded.build()
        assertEquals("the schema must stay at the frozen version", 38L, seeded.schemaVersion)
        assertEquals("every version row must carry its statistics projection", 0L, seeded.missingProjectionRows)
        assertEquals(
            "the sample is exactly the V-14 tier",
            P806ReportDeviceTimingFacts.TIER_50K.toLong(),
            seeded.transactionCount,
        )

        // (1) The report period read.
        seeded.timingDriver.reset()
        val report =
            success(
                seeded.adapter.readReportSnapshot(
                    P806ReportRequest(
                        ledgerId = P806ReportDeviceTimingFacts.ledgerId,
                        catalog = seeded.catalog,
                        period = P806Period(P806PeriodKind.MONTH),
                        today = seeded.today,
                        futureFromEpochNanos = seeded.futureFromEpochNanos,
                        expectedCatalogVersion = seeded.catalogVersion,
                    ),
                ),
            )
        val periodAmount = seeded.timingDriver.postingTotals.snapshot()
        assertEquals(
            "the report read runs its fixed statement set",
            P806ReportDeviceTimingFacts.REPORT_STATEMENT_COUNT,
            seeded.timingDriver.total
                .snapshot()
                .statements,
        )
        assertEquals("the period amount is one statement", 1, periodAmount.statements)
        assertEquals("the report period must cover the whole sample", TIER, report.transactionCount)
        assertEquals("the report detail count is the sample", TIER, report.detailCount)
        assertEquals("the report carries its first page", PAGE_SIZE, report.firstPage.rows.size)
        assertEquals("the report window is the resolved month", seeded.period.window, report.window)
        // The hidden expense account is the only contributing (EXPENSE/INCOME) posting account in
        // the seed, so its gross positive sum is the whole period amount.
        val periodTotals = report.postingTotals.single()
        assertEquals(P806ReportDeviceTimingFacts.EXPENSE_ACCOUNT_ID, periodTotals.accountId.value)
        assertEquals(seeded.expectedPeriodPositiveMinor, periodTotals.positiveMinorUnits)
        assertEquals(0L, periodTotals.refundMinorUnits)
        assertEquals(seeded.expectedPeriodPositiveMinor, periodTotals.totalMinorUnits)
        assertEquals("one currency count row", 1, report.currencyCounts.size)
        assertEquals(TIER, report.currencyCounts.single().transactionCount)
        assertEquals(seeded.expectedFutureTransactionCount, report.futureTransactionCount)

        // (2) The calendar bucket read.
        seeded.timingDriver.reset()
        val calendar =
            success(
                seeded.adapter.readCalendarSnapshot(
                    P806CalendarRequest(
                        ledgerId = P806ReportDeviceTimingFacts.ledgerId,
                        catalog = seeded.catalog,
                        month = seeded.month,
                        expectedCatalogVersion = seeded.catalogVersion,
                    ),
                ),
            )
        val calendarBuckets = seeded.timingDriver.total.snapshot()
        assertEquals(
            "the calendar read runs four fixed statements plus two per day bucket",
            P806ReportDeviceTimingFacts.CALENDAR_FIXED_STATEMENT_COUNT + 2 * calendar.days.size,
            calendarBuckets.statements,
        )
        assertEquals("one bucket per natural day of the month", seeded.period.dayCount, calendar.days.size)
        assertTrue("the month has at most 31 natural day buckets", calendar.days.size in 28..31)
        assertEquals(
            "the month starts on the resolved window start",
            seeded.period.window.startEpochNanos,
            calendar.days
                .first()
                .window.startEpochNanos,
        )
        assertEquals(
            "the month ends on the resolved window end",
            seeded.period.window.endEpochNanos,
            calendar.days
                .last()
                .window.endEpochNanos,
        )
        assertEquals("the month's contributions are the whole sample", TIER, calendar.days.sumOf { it.transactionCount })
        assertEquals(
            "the writer's captured day buckets are the read's buckets",
            seeded.dayBuckets.map { it.date },
            calendar.days.map { it.date },
        )
        calendar.days.forEachIndexed { index, day ->
            assertEquals(
                "day ${day.date} is the captured natural-day bucket",
                seeded.dayBuckets[index].window,
                day.window,
            )
            assertEquals(
                "day ${day.date} transaction count",
                seeded.expectedDayTransactionCounts[index],
                day.transactionCount,
            )
            val dayTotal = day.postingTotals.single()
            assertEquals(
                "day ${day.date} exact positive minor units",
                seeded.expectedDayPositiveMinor[index],
                dayTotal.positiveMinorUnits,
            )
            assertEquals(0L, dayTotal.refundMinorUnits)
            assertEquals(seeded.expectedDayPositiveMinor[index], dayTotal.totalMinorUnits)
        }

        // (3) + (4) The drilldown page and count statements, attributed separately inside one
        // production read transaction.
        seeded.timingDriver.reset()
        val drilldown =
            success(
                seeded.adapter.readDrilldownSnapshot(
                    P806DrilldownRequest(
                        ledgerId = P806ReportDeviceTimingFacts.ledgerId,
                        window = seeded.period.window,
                        expectedCatalogVersion = seeded.catalogVersion,
                    ),
                ),
            )
        val firstPage = seeded.timingDriver.page.snapshot()
        val count = seeded.timingDriver.count.snapshot()
        assertEquals(
            "the drilldown read runs its fixed statement set",
            P806ReportDeviceTimingFacts.DRILLDOWN_STATEMENT_COUNT,
            seeded.timingDriver.total
                .snapshot()
                .statements,
        )
        assertEquals("the drilldown total is the sample", TIER, drilldown.totalCount)
        assertEquals("the frozen first page size", PAGE_SIZE, drilldown.page.rows.size)
        assertNotNull("the sample needs a following page", drilldown.page.nextCursor)
        assertEquals("exactly one page statement", 1, firstPage.statements)
        assertEquals("exactly one count statement", 1, count.statements)

        val readings =
            linkedMapOf(
                "periodAmountMs" to periodAmount.millis,
                "calendarBucketsMs" to calendarBuckets.millis,
                "firstPageMs" to firstPage.millis,
                "countMs" to count.millis,
            )
        assertEquals("the four readings must be separately attributable", 4, readings.size)
        assertEquals(setOf("periodAmountMs", "calendarBucketsMs", "firstPageMs", "countMs"), readings.keys)
        readings.forEach { (label, value) ->
            assertTrue(
                "$label must be a three-place millisecond reading, got $value",
                Regex("[0-9]+\\.[0-9]{3}").matches(value),
            )
        }

        // One short line per metric so the dispatch gate can require each of the four exactly
        // once and parse its millisecond value and real statement count; the summary line carries
        // the structural facts.
        listOf(
            "periodAmountMs" to periodAmount,
            "calendarBucketsMs" to calendarBuckets,
            "firstPageMs" to firstPage,
            "countMs" to count,
        ).forEach { (label, reading) ->
            Log.i(TAG, "$PREFIX reading=$label ms=${reading.millis} statements=${reading.statements}")
        }
        Log.i(
            TAG,
            "$PREFIX summary fixture=${seeded.transactionCount} month=${seeded.month} " +
                "pageRows=${drilldown.page.rows.size} days=${calendar.days.size} schema=${seeded.schemaVersion}",
        )
    }

    private inline fun <reified T> success(result: P806SnapshotResult<T>): T =
        when (result) {
            is P806SnapshotResult.Success -> result.snapshot
            is P806SnapshotResult.Failed -> throw AssertionError("the read must succeed, got $result")
        }

    private fun P806TimingSqlDriver.Reading.snapshot(): ReadingSnapshot = ReadingSnapshot(millis(), statements)

    private class ReadingSnapshot(
        val millis: String,
        val statements: Int,
    )

    private companion object {
        const val TAG = "UnifiedLedger"
        const val PREFIX = "P806-V14DEV"
        const val TIMING_ARGUMENT = "ul.p806.timing"
        const val TIMING_ENABLED = "1"
        const val PAGE_SIZE = 50
        val TIER: Int = P806ReportDeviceTimingFacts.TIER_50K
    }
}
