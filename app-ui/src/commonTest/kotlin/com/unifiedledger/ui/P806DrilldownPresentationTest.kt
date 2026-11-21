package com.unifiedledger.ui

import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.LedgerEntryRow
import com.unifiedledger.application.P806Period
import com.unifiedledger.application.P806PeriodKind
import com.unifiedledger.application.P806ReportView
import com.unifiedledger.application.P806ResolvedPeriod
import com.unifiedledger.application.P806SnapshotFailure
import com.unifiedledger.application.P806TransactionCursor
import com.unifiedledger.application.P806TransactionPage
import com.unifiedledger.application.P806Window
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P8-06 batch 3 (D-362 1/2; spec sections 3.7/3.9/3.10.4): the drilldown's pure presentation
 * decisions — the report region's entry eligibility, the two target constructors (period and
 * category), the six payload faces and the header label. No Compose runtime, anonymous synthetic
 * data only. The screen's row/group rendering reuses `p8GroupByDay`/`LedgerEntryFlowRow` and is
 * not re-decided here.
 */
class P806DrilldownPresentationTest {
    private val ledgerId = LedgerId("ledger-drilldown-presentation")
    private val cny = CurrencyUnit("CNY", 2)
    private val categoryId = CategoryId("category-food")
    private val window = P806Window(startEpochNanos = 1_000L, endEpochNanos = 2_000L)
    private val asOf = Instant.parse("2026-11-21T02:00:00Z")

    private fun resolvedPeriod(): P806ResolvedPeriod =
        P806ResolvedPeriod(
            window = window,
            startDate = LocalDate(2026, 11, 1),
            endDateInclusive = LocalDate(2026, 11, 30),
            dayCount = 30,
        )

    private fun reportView(period: P806ResolvedPeriod? = resolvedPeriod()): P806ReportView =
        P806ReportView(
            ledgerId = ledgerId,
            period = period,
            currencies = emptyList(),
            expenseCategories = emptyList(),
            incomeCategories = emptyList(),
            uncategorizedExpenseTotals = emptyList(),
            uncategorizedIncomeTotals = emptyList(),
            transactionCount = if (period == null) 0 else 1,
            futureTransactionCount = 0,
            detailPage = P806TransactionPage(rows = emptyList(), nextCursor = null),
            detailCount = if (period == null) 0 else 1,
            span = null,
        )

    private fun slot(
        landed: P806ReportView? = reportView(),
        loading: Boolean = false,
        failure: P806SnapshotFailure? = null,
        writeStale: Boolean = false,
    ): P806ReportRegionState =
        P806ReportRegionState(
            period = P806Period(P806PeriodKind.MONTH),
            kind = P806PeriodKind.MONTH,
            loading = loading,
            failure = failure,
            landed = landed,
            landedKind = if (landed == null) null else P806PeriodKind.MONTH,
            writeStale = writeStale,
        )

    private fun row(id: String): LedgerEntryRow =
        LedgerEntryRow(
            transactionId = TransactionId(id),
            currentVersionId = TransactionVersionId("version-$id"),
            kind = TransactionKind.EXPENSE,
            occurredAt = Instant.parse("2026-11-20T02:00:00Z"),
            statisticsAt = Instant.parse("2026-11-20T02:00:00Z"),
            note = null,
            postings = emptyList(),
        )

    private fun periodTarget(): ReportDrilldownTarget.Period = ReportDrilldownTarget.Period(window)

    private fun drilldown(
        target: ReportDrilldownTarget = periodTarget(),
        rows: List<LedgerEntryRow> = emptyList(),
        totalCount: Int? = null,
        rowsLoading: Boolean = false,
        failure: P806SnapshotFailure? = null,
        unwired: Boolean = false,
        landedAsOf: Instant? = null,
    ): P503AppState.ReportDrilldown =
        P503AppState.ReportDrilldown(
            overview =
                P503AppState.OverviewEmpty(
                    state = LedgerCurrentState(ledgerId = ledgerId, transactions = emptyList(), balances = emptyList()),
                    selectedTab = P503Tab.ANALYSIS,
                ),
            target = target,
            loadedRows = rows,
            totalCount = totalCount,
            nextCursor = if (rowsLoading) P806TransactionCursor(1L, "tx-1") else null,
            rowsLoading = rowsLoading,
            failure = failure,
            unwired = unwired,
            landedAsOf = landedAsOf,
        )

    // ---------------------------------------------------------------- entry eligibility

    @Test
    fun theEntryNeedsAWiredGraphAConcludedSuccessAndAPeriodWindow() {
        assertTrue(p806ReportDrilldownEntryVisible(slot(), wired = true))
        // 未接线不渲染（no slot and/or no capability probe).
        assertTrue(!p806ReportDrilldownEntryVisible(slot(), wired = false))
        assertTrue(!p806ReportDrilldownEntryVisible(null, wired = true))
        // 尚未加载 / 读取中 / 失败 / 写入陈旧 all withhold the entry (no fabricated zero inside).
        assertTrue(!p806ReportDrilldownEntryVisible(slot(landed = null), wired = true))
        assertTrue(!p806ReportDrilldownEntryVisible(slot(loading = true), wired = true))
        assertTrue(!p806ReportDrilldownEntryVisible(slot(failure = P806SnapshotFailure.Unavailable), wired = true))
        assertTrue(!p806ReportDrilldownEntryVisible(slot(writeStale = true), wired = true))
        // Empty ledger / ALL with no window: the landed success has no period, so no entry.
        assertTrue(!p806ReportDrilldownEntryVisible(slot(landed = reportView(period = null)), wired = true))
        // An EMPTY (zero-contribution) report WITH a period still opens the period drilldown; the
        // category entries are withheld by the screen because no category row exists.
        assertTrue(p806ReportDrilldownEntryVisible(slot(landed = reportView().copy(transactionCount = 0, detailCount = 0)), wired = true))
    }

    @Test
    fun theTargetConstructorsNeverFabricateAWindow() {
        assertEquals(ReportDrilldownTarget.Period(window), p806DrilldownPeriodTarget(slot()))
        assertEquals(
            ReportDrilldownTarget.Category(window = window, categoryId = categoryId, categoryName = "餐饮"),
            p806DrilldownCategoryTarget(slot(), categoryId = categoryId, categoryName = "餐饮"),
        )
        // Every ineligible slot answers null instead of an unusable target.
        assertNull(p806DrilldownPeriodTarget(null))
        assertNull(p806DrilldownPeriodTarget(slot(loading = true)))
        assertNull(p806DrilldownPeriodTarget(slot(landed = null)))
        assertNull(p806DrilldownPeriodTarget(slot(landed = reportView(period = null))))
        assertNull(p806DrilldownCategoryTarget(slot(landed = reportView(period = null)), categoryId, "餐饮"))
        assertNull(p806DrilldownCategoryTarget(slot(writeStale = true), categoryId, "餐饮"))
    }

    // ---------------------------------------------------------------- the six faces

    @Test
    fun theSixFacesAreDistinguishableAndNeverCollapseIntoAFakeZero() {
        assertEquals(P806DrilldownFace.UNWIRED, p806DrilldownFace(drilldown(unwired = true)))
        assertEquals(P806DrilldownFace.NOT_LOADED, p806DrilldownFace(drilldown()))
        assertEquals(P806DrilldownFace.LOADING, p806DrilldownFace(drilldown(rows = listOf(row("tx-1")), totalCount = 1, rowsLoading = true)))
        assertEquals(P806DrilldownFace.LOADING, p806DrilldownFace(drilldown(rowsLoading = true)))
        assertEquals(P806DrilldownFace.FAILED, p806DrilldownFace(drilldown(rows = listOf(row("tx-1")), totalCount = 1, failure = P806SnapshotFailure.Unavailable)))
        assertEquals(P806DrilldownFace.FAILED, p806DrilldownFace(drilldown(failure = P806SnapshotFailure.Unavailable)))
        assertEquals(P806DrilldownFace.READY_EMPTY, p806DrilldownFace(drilldown(totalCount = 0)))
        assertEquals(P806DrilldownFace.READY_NON_EMPTY, p806DrilldownFace(drilldown(rows = listOf(row("tx-1")), totalCount = 1)))
        // A synthetic inconsistency (count 0 with rows) presents as non-empty for diagnosis: it
        // never claims the empty success.
        assertEquals(P806DrilldownFace.READY_NON_EMPTY, p806DrilldownFace(drilldown(rows = listOf(row("tx-1")), totalCount = 0)))
        // Loading wins over an old failure, so an in-flight retry is never shown as a concluded
        // failure; unwired wins over everything.
        assertEquals(P806DrilldownFace.LOADING, p806DrilldownFace(drilldown(rowsLoading = true, failure = P806SnapshotFailure.Unavailable)))
        assertEquals(P806DrilldownFace.UNWIRED, p806DrilldownFace(drilldown(unwired = true, rowsLoading = true)))
    }

    // ---------------------------------------------------------------- the header label

    @Test
    fun theHeaderLabelNamesTheTargetAndAppendsTheReadInstantWhenOneWasCaptured() {
        assertEquals("本期全部明细", p806DrilldownHeaderLabel(drilldown()))
        assertEquals(
            "餐饮",
            p806DrilldownHeaderLabel(drilldown(target = ReportDrilldownTarget.Category(window = window, categoryId = categoryId, categoryName = "餐饮"))),
        )
        // The as-of is only appended when the read captured one; a clock-less read stays unlabelled.
        assertEquals("本期全部明细 · 读取时点 $asOf", p806DrilldownHeaderLabel(drilldown(landedAsOf = asOf)))
        assertEquals(
            "餐饮 · 读取时点 $asOf",
            p806DrilldownHeaderLabel(
                drilldown(
                    target = ReportDrilldownTarget.Category(window = window, categoryId = categoryId, categoryName = "餐饮"),
                    landedAsOf = asOf,
                ),
            ),
        )
        assertNull(drilldown(landedAsOf = null).landedAsOf)
        assertNotNull(drilldown(landedAsOf = asOf).landedAsOf)
    }

    // ---------------------------------------------------------------- the fail-closed row/footer helpers

    @Test
    fun theOldContextLabelAndFooterPinTheFailClosedStates() {
        val loaded = drilldown(rows = listOf(row("tx-1")), totalCount = 1)
        // A settled success has no old-context caption and ends the walk honestly.
        assertNull(p806DrilldownOldContextText(loaded))
        assertEquals(FlowFooterState.END, p806DrilldownFooterState(loaded))
        // A continuation keeps the previous rows labelled as old context and says the page is loading.
        val loading = loaded.copy(rowsLoading = true, nextCursor = P806TransactionCursor(1L, "tx-1"))
        assertEquals("以下为上次成功读取的内容，可能已过期。", p806DrilldownOldContextText(loading))
        assertEquals(FlowFooterState.LOADING, p806DrilldownFooterState(loading))
        // A failed request keeps the same label and offers the retry instead of a fake end.
        val failed = loaded.copy(failure = P806SnapshotFailure.Unavailable)
        assertEquals("以下为上次成功读取的内容，可能已过期。", p806DrilldownOldContextText(failed))
        assertEquals(FlowFooterState.RETRY, p806DrilldownFooterState(failed))
        // A held cursor is the only FETCH_MORE condition.
        val paged = loaded.copy(nextCursor = P806TransactionCursor(1L, "tx-1"))
        assertEquals(FlowFooterState.FETCH_MORE, p806DrilldownFooterState(paged))
        // A never-landed surface has nothing to label (covered by the NOT_LOADED face).
        assertNull(p806DrilldownOldContextText(drilldown()))
    }
}
