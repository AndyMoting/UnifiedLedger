package com.unifiedledger.ui

import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.LedgerEntryRow
import com.unifiedledger.application.P806DrilldownView
import com.unifiedledger.application.P806SnapshotFailure
import com.unifiedledger.application.P806SnapshotResult
import com.unifiedledger.application.P806TransactionCursor
import com.unifiedledger.application.P806TransactionPage
import com.unifiedledger.application.P806Window
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P8-06 batch 3 (D-362 1/2; spec sections 3.7/3.9/3.10.4): the `ReportDrilldown` reducer.
 *
 * The state is one full-screen secondary surface fed by one keyset-paged read (page 50, day-grouped
 * for display). These assertions cover the frozen acceptance shape without a Compose runtime: open,
 * the host token gate (single flight by token), first page vs append, one negative per landing
 * verdict, the stale-provenance continuation reset, the typed refused/unwired faces, close/back to
 * the same overview instance, the read-only detail round trip, and the one-shot scroll restore.
 * Anonymous synthetic data only; zero IO.
 */
class P806DrilldownReducerTest {
    private val ledgerId = LedgerId("ledger-drilldown-reducer")
    private val otherLedgerId = LedgerId("ledger-drilldown-other")
    private val categoryId = CategoryId("category-food")
    private val cny = CurrencyUnit("CNY", 2)
    private val window = P806Window(startEpochNanos = 1_000L, endEpochNanos = 2_000L)
    private val asOf = Instant.parse("2026-11-21T02:00:00Z")
    private val reducer = P503ReducerImpl(ParseManualExpenseAmount(), cny)

    private val target = ReportDrilldownTarget.Period(window)
    private val categoryTarget = ReportDrilldownTarget.Category(window = window, categoryId = categoryId, categoryName = "餐饮")

    private fun overview(ledger: LedgerId = ledgerId): P503AppState.OverviewEmpty =
        P503AppState.OverviewEmpty(
            state = LedgerCurrentState(ledgerId = ledger, transactions = emptyList(), balances = emptyList()),
            selectedTab = P503Tab.ANALYSIS,
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

    private fun cursor(id: String): P806TransactionCursor = P806TransactionCursor(statisticsAtEpochNanos = 500L, transactionId = id)

    private fun view(
        rows: List<LedgerEntryRow> = emptyList(),
        nextCursor: P806TransactionCursor? = null,
        totalCount: Int = rows.size,
        viewWindow: P806Window = window,
        viewLedger: LedgerId = ledgerId,
    ): P806DrilldownView =
        P806DrilldownView(
            ledgerId = viewLedger,
            window = viewWindow,
            page = P806TransactionPage(rows = rows, nextCursor = nextCursor),
            totalCount = totalCount,
        )

    private fun identity(
        token: Long,
        cursor: P806TransactionCursor? = null,
        target: ReportDrilldownTarget = this.target,
        ledger: LedgerId = ledgerId,
        pageSetGeneration: Generation? = null,
        pageSetCatalogVersion: Long? = null,
    ): P806DrilldownRequestIdentity =
        P806DrilldownRequestIdentity(
            token = token,
            ledgerId = ledger,
            target = target,
            cursor = cursor,
            pageSetGeneration = pageSetGeneration,
            pageSetCatalogVersion = pageSetCatalogVersion,
        )

    private fun landed(
        request: P806DrilldownRequestIdentity,
        result: P806SnapshotResult<P806DrilldownView>,
        generation: Generation = 3,
        catalogVersion: Long = 7L,
        readAsOf: Instant? = asOf,
    ): P806DrilldownPageRead.Landed =
        P806DrilldownPageRead.Landed(
            request = request,
            capture = P806DrilldownReadCapture(generation = generation, catalogVersion = catalogVersion, asOf = readAsOf),
            result = result,
        )

    private fun drilldown(
        overview: P503AppState.OverviewEmpty = overview(),
        target: ReportDrilldownTarget = this.target,
        rows: List<LedgerEntryRow> = emptyList(),
        totalCount: Int? = null,
        nextCursor: P806TransactionCursor? = null,
        loading: Boolean = false,
        failure: P806SnapshotFailure? = null,
        token: Long = 0L,
        listScroll: ListScrollPosition? = null,
    ): P503AppState.ReportDrilldown =
        P503AppState.ReportDrilldown(
            overview = overview,
            target = target,
            loadedRows = rows,
            totalCount = totalCount,
            nextCursor = nextCursor,
            rowsLoading = loading,
            failure = failure,
            pageRequestToken = token,
            loadedGeneration = if (totalCount == null) null else 3,
            loadedCatalogVersion = if (totalCount == null) null else 7L,
            landedAsOf = if (totalCount == null) null else asOf,
            listScroll = listScroll,
        )

    // ---------------------------------------------------------------- open / request gate

    @Test
    fun openingEntersTheDrilldownFromTheOverviewAndReadsNothing() {
        val source = overview()
        val opened = assertIs<P503AppState.ReportDrilldown>(reducer.reduce(source, P503UiEvent.OpenReportDrilldown(target)))
        // The overview instance is the return target; opening performs no read (the host requests
        // the first page right after, the same split the account-flow and search surfaces use).
        assertSame(source, opened.overview)
        assertEquals(target, opened.target)
        assertTrue(opened.loadedRows.isEmpty())
        assertNull(opened.totalCount)
        assertEquals(0L, opened.pageRequestToken)
        assertTrue(!opened.rowsLoading)
        assertEquals(P806DrilldownFace.NOT_LOADED, p806DrilldownFace(opened))
    }

    @Test
    fun theRequestTokenGateAcceptsOnlyANewerTokenAndLeavesAllThreeFieldsUntouchedOtherwise() {
        val idle = drilldown()
        val requested =
            assertIs<P503AppState.ReportDrilldown>(
                reducer.reduce(idle, P503UiEvent.RequestReportDrilldownPage(requestToken = 1L)),
            )
        assertEquals(1L, requested.pageRequestToken)
        assertTrue(requested.rowsLoading)
        // A duplicate and an older token are absorbed with zero change (single flight by token).
        assertSame(requested, reducer.reduce(requested, P503UiEvent.RequestReportDrilldownPage(requestToken = 1L)))
        assertSame(requested, reducer.reduce(requested, P503UiEvent.RequestReportDrilldownPage(requestToken = 0L)))
        // A newer token is accepted and clears the stale failure while marking the read in flight.
        val retried =
            assertIs<P503AppState.ReportDrilldown>(
                reducer.reduce(
                    requested.copy(failure = P806SnapshotFailure.Unavailable),
                    P503UiEvent.RequestReportDrilldownPage(requestToken = 2L),
                ),
            )
        assertEquals(2L, retried.pageRequestToken)
        assertTrue(retried.rowsLoading)
        assertNull(retried.failure)
    }

    // ---------------------------------------------------------------- landing accept

    @Test
    fun theFirstPageLandsWholeAndRecordsItsProvenanceAsOfAndCount() {
        val state = drilldown(loading = true, token = 1L)
        val read = landed(identity(token = 1L), P806SnapshotResult.Success(view(rows = listOf(row("tx-1")), nextCursor = cursor("tx-1"), totalCount = 2)))
        val landedState =
            assertIs<P503AppState.ReportDrilldown>(
                reducer.reduce(state, P503UiEvent.ReportDrilldownPageLanded(read, currentGeneration = 3, currentCatalogVersion = 7L)),
            )
        assertEquals(listOf("tx-1"), landedState.loadedRows.map { entry -> entry.transactionId.value })
        assertEquals(2, landedState.totalCount)
        assertEquals(cursor("tx-1"), landedState.nextCursor)
        assertTrue(!landedState.rowsLoading)
        assertNull(landedState.failure)
        assertTrue(!landedState.unwired)
        assertEquals(3, landedState.loadedGeneration)
        assertEquals(7L, landedState.loadedCatalogVersion)
        assertEquals(asOf, landedState.landedAsOf)
        assertEquals(P806DrilldownFace.READY_NON_EMPTY, p806DrilldownFace(landedState))
    }

    @Test
    fun aContinuationAppendsItsRowsAndReplacesTheCountAndCursor() {
        val state = drilldown(rows = listOf(row("tx-1")), totalCount = 3, nextCursor = cursor("tx-1"), loading = true, token = 2L)
        val read = landed(identity(token = 2L, cursor = cursor("tx-1")), P806SnapshotResult.Success(view(rows = listOf(row("tx-2")), nextCursor = null, totalCount = 3)))
        val landedState =
            assertIs<P503AppState.ReportDrilldown>(
                reducer.reduce(state, P503UiEvent.ReportDrilldownPageLanded(read, currentGeneration = 3, currentCatalogVersion = 7L)),
            )
        assertEquals(listOf("tx-1", "tx-2"), landedState.loadedRows.map { entry -> entry.transactionId.value })
        assertEquals(3, landedState.totalCount)
        assertNull(landedState.nextCursor)
        assertTrue(!landedState.rowsLoading)
    }

    @Test
    fun anEmptySuccessIsItsOwnReadyEmptyFace() {
        val read = landed(identity(token = 1L), P806SnapshotResult.Success(view(rows = emptyList(), totalCount = 0)))
        val landedState =
            assertIs<P503AppState.ReportDrilldown>(
                reducer.reduce(drilldown(loading = true, token = 1L), P503UiEvent.ReportDrilldownPageLanded(read, 3, 7L)),
            )
        assertEquals(P806DrilldownFace.READY_EMPTY, p806DrilldownFace(landedState))
    }

    // ---------------------------------------------------------------- landing drops

    @Test
    fun aStaleTokenTargetCursorOrLedgerDropsTheLandingWhole() {
        val state = drilldown(rows = listOf(row("tx-1")), totalCount = 2, nextCursor = cursor("tx-1"), loading = true, token = 2L)
        // Superseded token.
        assertSame(
            state,
            reducer.reduce(
                state,
                P503UiEvent.ReportDrilldownPageLanded(
                    landed(identity(token = 1L, cursor = cursor("tx-1")), P806SnapshotResult.Success(view())),
                    3,
                    7L,
                ),
            ),
        )
        // Another target.
        assertSame(
            state,
            reducer.reduce(
                state,
                P503UiEvent.ReportDrilldownPageLanded(
                    landed(identity(token = 2L, cursor = cursor("tx-1"), target = categoryTarget), P806SnapshotResult.Success(view())),
                    3,
                    7L,
                ),
            ),
        )
        // A cursor that is no longer the continuation point.
        assertSame(
            state,
            reducer.reduce(
                state,
                P503UiEvent.ReportDrilldownPageLanded(
                    landed(identity(token = 2L, cursor = cursor("tx-other")), P806SnapshotResult.Success(view())),
                    3,
                    7L,
                ),
            ),
        )
        // The surface was carried from another ledger.
        assertSame(
            state,
            reducer.reduce(
                state,
                P503UiEvent.ReportDrilldownPageLanded(
                    landed(identity(token = 2L, cursor = cursor("tx-1"), ledger = otherLedgerId), P806SnapshotResult.Success(view())),
                    3,
                    7L,
                ),
            ),
        )
    }

    @Test
    fun aGenerationAdvanceAndACatalogAdvanceEndOnlyThatRequestAndResetTheRetry() {
        val state = drilldown(rows = listOf(row("tx-1")), totalCount = 2, nextCursor = cursor("tx-1"), loading = true, token = 2L)
        val read = landed(identity(token = 2L, cursor = cursor("tx-1")), P806SnapshotResult.Success(view()))
        val generationAdvanced =
            assertIs<P503AppState.ReportDrilldown>(
                reducer.reduce(state, P503UiEvent.ReportDrilldownPageLanded(read, currentGeneration = 4, currentCatalogVersion = 7L)),
            )
        assertTrue(!generationAdvanced.rowsLoading)
        assertEquals(P806SnapshotFailure.Unavailable, generationAdvanced.failure)
        assertNull(generationAdvanced.nextCursor)
        assertEquals(listOf("tx-1"), generationAdvanced.loadedRows.map { entry -> entry.transactionId.value })
        val catalogAdvanced =
            assertIs<P503AppState.ReportDrilldown>(
                reducer.reduce(state, P503UiEvent.ReportDrilldownPageLanded(read, currentGeneration = 3, currentCatalogVersion = 8L)),
            )
        assertTrue(!catalogAdvanced.rowsLoading)
        assertEquals(P806SnapshotFailure.CatalogVersionMismatch, catalogAdvanced.failure)
        assertNull(catalogAdvanced.nextCursor)
    }

    @Test
    fun aPayloadFromAnotherLedgerOrWindowIsRejectedAsATypedInvalidState() {
        val state = drilldown(loading = true, token = 1L)
        val read = landed(identity(token = 1L), P806SnapshotResult.Success(view(viewLedger = otherLedgerId)))
        val rejected =
            assertIs<P503AppState.ReportDrilldown>(
                reducer.reduce(state, P503UiEvent.ReportDrilldownPageLanded(read, 3, 7L)),
            )
        assertTrue(!rejected.rowsLoading)
        assertEquals(P806SnapshotFailure.InvalidState, rejected.failure)
    }

    @Test
    fun aSuccessClaimingZeroTotalWithRowsIsRejectedAndItsRowsNeverLand() {
        val state = drilldown(loading = true, token = 1L)
        val read = landed(identity(token = 1L), P806SnapshotResult.Success(view(rows = listOf(row("tx-1")), totalCount = 0)))
        val rejected =
            assertIs<P503AppState.ReportDrilldown>(
                reducer.reduce(state, P503UiEvent.ReportDrilldownPageLanded(read, 3, 7L)),
            )
        // The self-contradicting payload is dropped whole — no rows and no count land, so the
        // frozen §2.5 invariant ("the reducer never produces totalCount == 0 with non-empty rows")
        // holds — and its typed failure face is shown instead.
        assertTrue(rejected.loadedRows.isEmpty())
        assertNull(rejected.totalCount)
        assertTrue(!rejected.rowsLoading)
        assertEquals(P806SnapshotFailure.InvalidState, rejected.failure)
        assertEquals(P806DrilldownFace.FAILED, p806DrilldownFace(rejected))
    }

    @Test
    fun aTypedFailedContinuationKeepsThePreviousRowsCountAndCursor() {
        val state = drilldown(rows = listOf(row("tx-1")), totalCount = 2, nextCursor = cursor("tx-1"), loading = true, token = 2L)
        val read =
            landed(
                identity(token = 2L, cursor = cursor("tx-1")),
                P806SnapshotResult.Failed(P806SnapshotFailure.Unavailable),
            )
        val failed =
            assertIs<P503AppState.ReportDrilldown>(
                reducer.reduce(state, P503UiEvent.ReportDrilldownPageLanded(read, 3, 7L)),
            )
        // Fail-closed: a legitimately failed continuation keeps the previous success visible and
        // keeps the cursor so the retry re-requests the SAME page; only the request ends.
        assertEquals(listOf("tx-1"), failed.loadedRows.map { entry -> entry.transactionId.value })
        assertEquals(2, failed.totalCount)
        assertEquals(cursor("tx-1"), failed.nextCursor)
        assertTrue(!failed.rowsLoading)
        assertEquals(P806SnapshotFailure.Unavailable, failed.failure)
    }

    @Test
    fun aStaleProvenanceContinuationKeepsTheOldRowsClearsTheCursorAndStaysLoading() {
        val state =
            drilldown(rows = listOf(row("tx-1")), totalCount = 3, nextCursor = cursor("tx-1"), loading = true, token = 3L)
        val stale = P806DrilldownPageRead.StaleProvenance(identity(token = 3L, cursor = cursor("tx-1"), pageSetGeneration = 3, pageSetCatalogVersion = 7L))
        val landedState =
            assertIs<P503AppState.ReportDrilldown>(
                reducer.reduce(state, P503UiEvent.ReportDrilldownPageLanded(stale, currentGeneration = 3, currentCatalogVersion = 7L)),
            )
        // The old page set survives; the old cursor is never reused; the host re-reads the FIRST
        // page with a new token and its landing replaces the set whole.
        assertEquals(listOf("tx-1"), landedState.loadedRows.map { entry -> entry.transactionId.value })
        assertNull(landedState.nextCursor)
        assertTrue(landedState.rowsLoading)
        assertNull(landedState.failure)
        assertEquals(P806DrilldownFace.LOADING, p806DrilldownFace(landedState))
        // The new first-page landing replaces the set whole (no splice of two worlds).
        val replacement =
            landed(identity(token = 4L), P806SnapshotResult.Success(view(rows = listOf(row("tx-9")), totalCount = 1)), generation = 3, catalogVersion = 7L)
        val replaced =
            assertIs<P503AppState.ReportDrilldown>(
                reducer.reduce(
                    landedState.copy(pageRequestToken = 4L),
                    P503UiEvent.ReportDrilldownPageLanded(replacement, 3, 7L),
                ),
            )
        assertEquals(listOf("tx-9"), replaced.loadedRows.map { entry -> entry.transactionId.value })
        assertEquals(1, replaced.totalCount)
        assertNull(replaced.nextCursor)
        assertTrue(!replaced.rowsLoading)
    }

    @Test
    fun aRefusedLeaseAndAnUnwiredGraphLandDistinguishableFaces() {
        val refusedRead = P806DrilldownPageRead.Refused(identity(token = 1L))
        val refused =
            assertIs<P503AppState.ReportDrilldown>(
                reducer.reduce(drilldown(loading = true, token = 1L), P503UiEvent.ReportDrilldownPageLanded(refusedRead, 3, 7L)),
            )
        assertTrue(!refused.rowsLoading)
        assertEquals(P806SnapshotFailure.Unavailable, refused.failure)
        assertEquals(P806DrilldownFace.FAILED, p806DrilldownFace(refused))
        val unwiredRead = P806DrilldownPageRead.Unwired(identity(token = 1L))
        val unwired =
            assertIs<P503AppState.ReportDrilldown>(
                reducer.reduce(drilldown(loading = true, token = 1L), P503UiEvent.ReportDrilldownPageLanded(unwiredRead, 3, 7L)),
            )
        assertTrue(!unwired.rowsLoading)
        assertTrue(unwired.unwired)
        assertEquals(P806DrilldownFace.UNWIRED, p806DrilldownFace(unwired))
    }

    @Test
    fun aStaleRefusedUnwiredOrProvenanceReadIsDroppedByTheTokenGate() {
        val state = drilldown(rows = listOf(row("tx-1")), totalCount = 2, nextCursor = cursor("tx-1"), loading = true, token = 2L)
        val staleRequest = identity(token = 1L, cursor = cursor("tx-1"))
        assertSame(state, reducer.reduce(state, P503UiEvent.ReportDrilldownPageLanded(P806DrilldownPageRead.Refused(staleRequest), 3, 7L)))
        assertSame(state, reducer.reduce(state, P503UiEvent.ReportDrilldownPageLanded(P806DrilldownPageRead.Unwired(staleRequest), 3, 7L)))
        assertSame(state, reducer.reduce(state, P503UiEvent.ReportDrilldownPageLanded(P806DrilldownPageRead.StaleProvenance(staleRequest), 3, 7L)))
    }

    // ---------------------------------------------------------------- close / back / detail round trip

    @Test
    fun closeAndBackReturnTheSameOverviewInstanceAndIssueNoRead() {
        val source = overview()
        val state = drilldown(overview = source, rows = listOf(row("tx-1")), totalCount = 1, loading = true, token = 5L)
        assertSame(source, reducer.reduce(state, P503UiEvent.CloseReportDrilldown))
        assertSame(source, reducer.reduce(state, P503UiEvent.Back))
        assertEquals(P806DrilldownFace.LOADING, p806DrilldownFace(state))
        assertEquals(5L, state.pageRequestToken)
    }

    @Test
    fun aRowOpensTheReadOnlyDetailCarryingTheDrilldownAndCloseReturnsItWhole() {
        val state = drilldown(rows = listOf(row("tx-1")), totalCount = 2, nextCursor = cursor("tx-1"))
        val entered =
            assertIs<P503AppState.TransactionDetail>(
                reducer.reduce(
                    state,
                    P503UiEvent.SelectTransaction(
                        transactionId = TransactionId("tx-1"),
                        result = com.unifiedledger.application.TransactionDetailResult.NotFound,
                    ),
                ),
            )
        val carried = assertIs<TransactionDetailReturnTo.ReportDrilldown>(entered.returnTo).carriedDrilldown
        // §2.4: the fill is the entry overview's selectedTab (semantically zero; the return
        // destination is carried by returnTo alone — the detail reducer never reads originTab).
        assertEquals(P503Tab.ANALYSIS, entered.originTab)
        // Nothing in flight and no sample: the carried surface is the SAME instance.
        assertSame(state, carried)
        assertSame(state, reducer.reduce(entered, P503UiEvent.CloseTransactionDetail))
        assertSame(state, reducer.reduce(entered, P503UiEvent.Back))
        // The detail carries a read-only detour: none of the three sub-surface entries.
        assertEquals(
            DetailSubSurfaceEntries(edit = false, void = false, annotationEdit = false),
            detailSubSurfaceEntriesVisible(entered.returnTo),
        )
    }

    @Test
    fun theSampledDrilldownScrollRidesTheCarriedSurfaceAndIsConsumedOnce() {
        val state = drilldown(rows = listOf(row("tx-1")), totalCount = 1)
        val sample = ListScrollPosition(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 240)
        val entered =
            assertIs<P503AppState.TransactionDetail>(
                reducer.reduce(
                    state,
                    P503UiEvent.SelectTransaction(
                        transactionId = TransactionId("tx-1"),
                        result = com.unifiedledger.application.TransactionDetailResult.NotFound,
                        homeFlowScroll = sample,
                    ),
                ),
            )
        val carried = assertIs<TransactionDetailReturnTo.ReportDrilldown>(entered.returnTo).carriedDrilldown
        assertEquals(sample, carried.listScroll)
        // The carried rows/count/cursor survive the detour untouched.
        assertEquals(1, carried.totalCount)
        assertEquals(listOf("tx-1"), carried.loadedRows.map { entry -> entry.transactionId.value })
        val returned = assertIs<P503AppState.ReportDrilldown>(reducer.reduce(entered, P503UiEvent.CloseTransactionDetail))
        assertEquals(sample, returned.listScroll)
        // The screen applied the restore: the slot clears exactly once (a second dispatch is a no-op).
        val consumed = assertIs<P503AppState.ReportDrilldown>(reducer.reduce(returned, P503UiEvent.ListScrollRestored))
        assertNull(consumed.listScroll)
        assertSame(consumed, reducer.reduce(consumed, P503UiEvent.ListScrollRestored))
    }

    @Test
    fun openingTheDetailDropsAnInFlightMarkerSoTheReturnedFooterIsNotStuckLoading() {
        val state = drilldown(rows = listOf(row("tx-1")), totalCount = 2, nextCursor = cursor("tx-1"), loading = true, token = 6L)
        val entered =
            assertIs<P503AppState.TransactionDetail>(
                reducer.reduce(
                    state,
                    P503UiEvent.SelectTransaction(
                        transactionId = TransactionId("tx-1"),
                        result = com.unifiedledger.application.TransactionDetailResult.NotFound,
                    ),
                ),
            )
        val carried = assertIs<TransactionDetailReturnTo.ReportDrilldown>(entered.returnTo).carriedDrilldown
        assertTrue(!carried.rowsLoading)
        // The host token survives (it belongs to the host's monotonic sequence).
        assertEquals(6L, carried.pageRequestToken)
    }

    // ---------------------------------------------------------------- absorption discipline

    @Test
    fun theNewEventFamilyIsAbsorbedOutsideItsDesignedStatesAndNeverThrows() {
        val ready = P503AppState.Ready()
        val overview = overview()
        val terminal = P503AppState.InfrastructureFailure(InfrastructureFailureContext.READ)
        for (state in listOf(ready, terminal)) {
            assertSame(state, reducer.reduce(state, P503UiEvent.OpenReportDrilldown(target)))
            assertSame(state, reducer.reduce(state, P503UiEvent.CloseReportDrilldown))
            assertSame(state, reducer.reduce(state, P503UiEvent.RequestReportDrilldownPage(1L)))
            assertSame(
                state,
                reducer.reduce(state, P503UiEvent.ReportDrilldownPageLanded(P806DrilldownPageRead.Refused(identity(token = 1L)), 3, 7L)),
            )
        }
        // On the overview the open event is the one designed transition; the other three are absorbed.
        val opened = assertIs<P503AppState.ReportDrilldown>(reducer.reduce(overview, P503UiEvent.OpenReportDrilldown(target)))
        assertSame(overview, opened.overview)
        assertSame(opened, reducer.reduce(opened, P503UiEvent.OpenReportDrilldown(categoryTarget)))
    }

    @Test
    fun aLandingThatArrivesAfterTheReadOnlyDetourOpenedIsAbsorbedWithoutTouchingTheDetail() {
        val source = overview()
        val state = drilldown(overview = source, rows = listOf(row("tx-1")), totalCount = 1, loading = true, token = 5L)
        val entered =
            assertIs<P503AppState.TransactionDetail>(
                reducer.reduce(
                    state,
                    P503UiEvent.SelectTransaction(
                        transactionId = TransactionId("tx-1"),
                        result = com.unifiedledger.application.TransactionDetailResult.NotFound,
                    ),
                ),
            )
        val read = landed(identity(token = 5L), P806SnapshotResult.Success(view(rows = listOf(row("tx-1")), totalCount = 2)))
        assertSame(entered, reducer.reduce(entered, P503UiEvent.ReportDrilldownPageLanded(read, 3, 7L)))
    }

    @Test
    fun aMonthlyOrBudgetLandingOnTheDrilldownIsAppliedToTheCarriedOverviewInsteadOfThrowing() {
        val state = drilldown()
        val monthly =
            com.unifiedledger.application.MonthlyActivityResult.Unavailable
        val failed = assertIs<P503AppState.InfrastructureFailure>(reducer.reduce(state, P503UiEvent.MonthlyActivityResult(monthly, emptyList())))
        assertEquals(InfrastructureFailureContext.READ, failed.context)
        assertSame(state.overview, failed.monthlyOverview)
        // The refresh family is absorbed (the fresh current state lands when the overview returns).
        assertSame(state, reducer.reduce(state, P503UiEvent.RefreshFailed))
    }

    @Test
    fun aDayTargetCarriesItsCalendarReturnAndCloseRestoresThatExactCalendar() {
        // P8-06 batch 4 (D-362 1): a day detail opened from the calendar returns the calendar the
        // user left — same month, payload and scroll — while period/category targets keep the
        // batch-3 overview return (the next test).
        val source = overview()
        val calendar =
            P503AppState.Calendar(
                overview = source,
                month = YearMonth(2026, kotlinx.datetime.Month.NOVEMBER),
                listScroll = ListScrollPosition(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 320),
            )
        val dayTarget = ReportDrilldownTarget.Day(window = window, date = LocalDate(2026, 11, 5))
        val day = P503AppState.ReportDrilldown(overview = source, target = dayTarget, returnToCalendar = calendar)
        assertSame(calendar, reducer.reduce(day, P503UiEvent.CloseReportDrilldown))
        assertSame(calendar, reducer.reduce(day, P503UiEvent.Back))
        // A day target is still the shared page's target: the header names it and it reads the same
        // window through the drilldown query.
        assertEquals(dayTarget, day.target)
        assertEquals(dayTarget.window, day.target.window)
    }

    @Test
    fun aPeriodTargetWithoutACalendarReturnKeepsTheOriginalOverviewReturn() {
        val overview = overview()
        val period = P503AppState.ReportDrilldown(overview = overview, target = target)
        assertSame(overview, reducer.reduce(period, P503UiEvent.CloseReportDrilldown))
        assertSame(overview, reducer.reduce(period, P503UiEvent.Back))
    }
}
