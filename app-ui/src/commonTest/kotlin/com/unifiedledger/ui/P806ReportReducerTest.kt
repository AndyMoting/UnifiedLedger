package com.unifiedledger.ui

import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.P408ReconciliationStatus
import com.unifiedledger.application.P801AccountReconciliationQueueRow
import com.unifiedledger.application.P801AccountReconciliationStatusTotal
import com.unifiedledger.application.P806CurrencyView
import com.unifiedledger.application.P806Period
import com.unifiedledger.application.P806PeriodKind
import com.unifiedledger.application.P806ReportView
import com.unifiedledger.application.P806ResolvedPeriod
import com.unifiedledger.application.P806SnapshotFailure
import com.unifiedledger.application.P806SnapshotResult
import com.unifiedledger.application.P806TransactionPage
import com.unifiedledger.application.P806Window
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P8-06 batch b (D-343 sections 3.1.3/3.7): the report slot's reducer transitions.
 *
 * The assertions cover acceptance A (one read starts from the real wiring: a fresh slot requests
 * exactly once per period, a duplicate selection or an unchanged condition never re-reads, an
 * unwired overview absorbs every report event), C (the same period only re-reads on the initial
 * load, a real period change, an explicit retry or a determinate write — the write path is held by
 * [P503HostCoordinatorTest]; a read-only detour preserves the period and the payload and starts no
 * read, and a landing over the queue detail's nested account-flow overview rebuilds that overview
 * while the queue page stays its own) and the identity half of B (an old token, another period or
 * another ledger never lands, and a late read never clears a newer request's loading).
 *
 * Anonymous synthetic amounts only; no Compose runtime, no timing.
 */
class P806ReportReducerTest {
    private val ledgerId = LedgerId("ledger-local-test")
    private val otherLedgerId = LedgerId("ledger-other")
    private val expenseAccountId = AccountId("expense-food")
    private val cny = CurrencyUnit("CNY", 2)
    private val readInstant = Instant.parse("2026-11-18T02:00:00Z")
    private val reducer = P503ReducerImpl(ParseManualExpenseAmount(), cny)

    private fun ledger(ledger: LedgerId = ledgerId): LedgerCurrentState = LedgerCurrentState(ledgerId = ledger, transactions = emptyList(), balances = emptyList())

    private fun overview(
        slot: P806ReportRegionState?,
        tab: P503Tab = P503Tab.ANALYSIS,
        ledger: LedgerId = ledgerId,
    ): P503AppState.OverviewEmpty = P503AppState.OverviewEmpty(state = ledger(ledger), selectedTab = tab, reportRegion = slot)

    private fun view(
        period: P806ResolvedPeriod? = null,
        netExpense: Long = 3_000L,
        ledger: LedgerId = ledgerId,
    ): P806ReportView =
        P806ReportView(
            ledgerId = ledger,
            period = period,
            currencies =
                listOf(
                    P806CurrencyView(
                        currency = cny,
                        ordinaryIncomeMinorUnits = 0L,
                        netExpenseMinorUnits = netExpense,
                        balanceMinorUnits = -netExpense,
                        positiveExpenseMinorUnits = netExpense,
                        refundMinorUnits = 0L,
                        dailyAverageMinorUnits = 100L,
                        transactionCount = 4,
                    ),
                ),
            expenseCategories = emptyList(),
            incomeCategories = emptyList(),
            uncategorizedExpenseTotals = emptyList(),
            uncategorizedIncomeTotals = emptyList(),
            transactionCount = 4,
            futureTransactionCount = 0,
            detailPage = P806TransactionPage(rows = emptyList(), nextCursor = null),
            detailCount = 4,
        )

    private fun resolvedMonth(): P806ResolvedPeriod =
        P806ResolvedPeriod(
            window = P806Window(startEpochNanos = 1_000L, endEpochNanos = 2_000L),
            startDate = LocalDate(2026, 11, 1),
            endDateInclusive = LocalDate(2026, 11, 30),
            dayCount = 30,
        )

    private fun loadedSlot(
        period: P806Period = P806Period(P806PeriodKind.MONTH),
        token: Long = 4L,
    ): P806ReportRegionState =
        P806ReportRegionState(
            period = period,
            kind = period.kind,
            requestToken = token,
            landed = view(resolvedMonth()),
            landedKind = period.kind,
            landedAsOf = readInstant,
            landedGeneration = 3,
            landedCatalogVersion = 7L,
        )

    private fun requestingSlot(
        token: Long,
        period: P806Period = P806Period(P806PeriodKind.MONTH),
        landed: P806ReportView? = null,
        writeStale: Boolean = false,
        fenceToken: Long = 0L,
    ): P806ReportRegionState =
        P806ReportRegionState(
            period = period,
            kind = period.kind,
            requestToken = token,
            fenceToken = fenceToken,
            loading = true,
            writeStale = writeStale,
            landed = landed,
            landedKind = landed?.let { period.kind },
            landedAsOf = landed?.let { readInstant },
            landedGeneration = landed?.let { 3 },
            landedCatalogVersion = landed?.let { 7L },
        )

    private fun failedRead(
        token: Long,
        period: P806Period = P806Period(P806PeriodKind.MONTH),
        generation: Generation = 3,
        catalogVersion: Long = 7L,
    ): P806ReportRead.Landed =
        P806ReportRead.Landed(
            capture =
                P806ReportReadCapture(
                    request = P806ReportRequestIdentity(token = token, ledgerId = ledgerId, period = period),
                    generation = generation,
                    catalogVersion = catalogVersion,
                    asOf = readInstant,
                ),
            result = P806SnapshotResult.Failed(P806SnapshotFailure.Unavailable),
        )

    private fun successRead(
        token: Long,
        period: P806Period = P806Period(P806PeriodKind.MONTH),
        generation: Generation = 3,
        catalogVersion: Long = 7L,
    ): P806ReportRead.Landed =
        P806ReportRead.Landed(
            capture =
                P806ReportReadCapture(
                    request = P806ReportRequestIdentity(token = token, ledgerId = ledgerId, period = period),
                    generation = generation,
                    catalogVersion = catalogVersion,
                    asOf = readInstant,
                ),
            result = P806SnapshotResult.Success(view(resolvedMonth())),
        )

    private fun queueRow(
        postingId: String = "posting-1",
        status: P408ReconciliationStatus = P408ReconciliationStatus.PENDING,
    ): P801AccountReconciliationQueueRow =
        P801AccountReconciliationQueueRow(
            postingId = PostingId(postingId),
            transactionId = TransactionId("tx-1"),
            occurredAt = readInstant,
            note = "转账",
            accountId = expenseAccountId,
            amountMinor = -10_000L,
            currency = cny,
            status = status,
            hasActiveEvidenceLink = false,
            transactionKind = TransactionKind.ACCOUNT_TRANSFER,
        )

    private fun queueTotal(): P801AccountReconciliationStatusTotal =
        P801AccountReconciliationStatusTotal(
            currency = cny,
            status = P408ReconciliationStatus.PENDING,
            totalMinorUnits = -10_000L,
            rowCount = 1,
        )

    // ---------------------------------------------------------------- A: the initial request and the five kinds

    @Test
    fun aFreshSlotStartsOnTheShanghaiCurrentMonthAndRequestsOnce() {
        // The slot's default carries no explicit month: the APPLICATION resolves today's month, so
        // the UI never owns a Shanghai conversion.
        assertEquals(P806Period(P806PeriodKind.MONTH), P806ReportRegionState().period)
        val started =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(overview(P806ReportRegionState()), P503UiEvent.SelectReportPeriod(P806Period(P806PeriodKind.MONTH), requestToken = 1L)),
            )
        val slot = assertNotNull(started.reportRegion)
        assertTrue(slot.loading)
        assertEquals(1L, slot.requestToken)
        assertEquals(P806Period(P806PeriodKind.MONTH), slot.period)
        // The same period again (a re-tap, a recomposition-driven dispatch) starts no second read.
        assertSame(started, reducer.reduce(started, P503UiEvent.SelectReportPeriod(P806Period(P806PeriodKind.MONTH), requestToken = 2L)))
    }

    @Test
    fun allFiveKindsAreSelectableAndOnlyARealChangeStartsANewRead() {
        // WEEK/MONTH/YEAR/ALL: the request carries the kind and the application resolves the window.
        val kinds = listOf(P806PeriodKind.WEEK, P806PeriodKind.MONTH, P806PeriodKind.YEAR, P806PeriodKind.ALL)
        var token = 0L
        for (kind in kinds) {
            token += 1
            val next =
                assertIs<P503AppState.OverviewEmpty>(
                    reducer.reduce(overview(loadedSlot()), P503UiEvent.SelectReportPeriod(P806Period(kind), requestToken = token)),
                )
            val slot = assertNotNull(next.reportRegion)
            assertEquals(P806Period(kind), slot.period)
            assertTrue(slot.loading)
            assertEquals(token, slot.requestToken)
            // The previous success stays as old context while the new read is in flight.
            assertNotNull(slot.landed)
        }
        // The custom window is a real change too, including an unparseable one (the application
        // answers the typed InvalidWindow; the host never silently rewrites the dates).
        token += 1
        val custom = P806Period(P806PeriodKind.CUSTOM, customStart = LocalDate(2026, 11, 1), customEnd = LocalDate(2026, 11, 16))
        val customState =
            assertIs<P503AppState.OverviewEmpty>(reducer.reduce(overview(loadedSlot()), P503UiEvent.SelectReportPeriod(custom, requestToken = token)))
        assertEquals(custom, assertNotNull(customState.reportRegion).period)
    }

    @Test
    fun aNewerPeriodSupersedesTheOldRequestAndTheATokenMustGrow() {
        val before = overview(loadedSlot(token = 4L))
        // A token that does not grow never restarts a read.
        assertSame(before, reducer.reduce(before, P503UiEvent.SelectReportPeriod(P806Period(P806PeriodKind.YEAR, year = 2025), requestToken = 4L)))
        val year2025 = P806Period(P806PeriodKind.YEAR, year = 2025)
        val superseded =
            assertIs<P503AppState.OverviewEmpty>(reducer.reduce(before, P503UiEvent.SelectReportPeriod(year2025, requestToken = 5L)))
        val slot = assertNotNull(superseded.reportRegion)
        assertEquals(year2025, slot.period)
        assertEquals(5L, slot.requestToken)
        assertTrue(slot.loading)
        // The OLD month read arriving late is absorbed: it neither lands nor clears the new request.
        assertSame(
            superseded,
            reducer.reduce(superseded, P503UiEvent.ReportPeriodResult(successRead(token = 4L), currentGeneration = 3, currentCatalogVersion = 7L)),
        )
    }

    // ---------------------------------------------------------------- B/D: typed failures and retry

    @Test
    fun aBadCustomRangeLandsAsATypedFailureAndKeepsTheLastSuccess() {
        val custom = P806Period(P806PeriodKind.CUSTOM, customStart = LocalDate(2026, 11, 16), customEnd = LocalDate(2026, 11, 1))
        val started =
            assertIs<P503AppState.OverviewEmpty>(reducer.reduce(overview(loadedSlot()), P503UiEvent.SelectReportPeriod(custom, requestToken = 5L)))
        val failed =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(
                    started,
                    P503UiEvent.ReportPeriodResult(
                        read =
                            P806ReportRead.Landed(
                                capture =
                                    P806ReportReadCapture(
                                        request = P806ReportRequestIdentity(token = 5L, ledgerId = ledgerId, period = custom),
                                        generation = 3,
                                        catalogVersion = 7L,
                                        asOf = readInstant,
                                    ),
                                result = P806SnapshotResult.Failed(P806SnapshotFailure.InvalidWindow),
                            ),
                        currentGeneration = 3,
                        currentCatalogVersion = 7L,
                    ),
                ),
            )
        val slot = assertNotNull(failed.reportRegion)
        assertTrue(!slot.loading)
        assertEquals(P806SnapshotFailure.InvalidWindow, slot.failure)
        // The dates were not silently changed and the previous success is still there as old context.
        assertEquals(custom, slot.period)
        assertNotNull(slot.landed)
        // Re-dispatching the SAME rejected period does not re-read; only an explicit retry does.
        assertSame(failed, reducer.reduce(failed, P503UiEvent.SelectReportPeriod(custom, requestToken = 6L)))
        val retried =
            assertIs<P503AppState.OverviewEmpty>(reducer.reduce(failed, P503UiEvent.RetryReportPeriod(requestToken = 6L)))
        val retrying = assertNotNull(retried.reportRegion)
        assertTrue(retrying.loading)
        assertNull(retrying.failure)
        assertEquals(6L, retrying.requestToken)
    }

    @Test
    fun aFirstReadFailureIsVisibleAsTheTypedFailureAndNeverAsAnEmptyPeriod() {
        val failed =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(
                    overview(P806ReportRegionState(requestToken = 1L, loading = true)),
                    P503UiEvent.ReportPeriodResult(
                        read =
                            P806ReportRead.Landed(
                                capture =
                                    P806ReportReadCapture(
                                        request = P806ReportRequestIdentity(token = 1L, ledgerId = ledgerId, period = P806Period(P806PeriodKind.MONTH)),
                                        generation = 3,
                                        catalogVersion = 7L,
                                        asOf = readInstant,
                                    ),
                                result = P806SnapshotResult.Failed(P806SnapshotFailure.Unavailable),
                            ),
                        currentGeneration = 3,
                        currentCatalogVersion = 7L,
                    ),
                ),
            )
        val slot = assertNotNull(failed.reportRegion)
        assertEquals(P806SnapshotFailure.Unavailable, slot.failure)
        assertNull(slot.landed)
        assertEquals(P806ReportRegionFace.FAILED, p806ReportRegionFace(slot))
    }

    @Test
    fun aCatalogAdvanceOrAGraphSwapEndsOnlyThatRequestAndKeepsTheOldPayloadLabelled() {
        val started =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(overview(loadedSlot(token = 4L)), P503UiEvent.SelectReportPeriod(P806Period(P806PeriodKind.YEAR, year = 2025), requestToken = 5L)),
            )
        val period = assertNotNull(started.reportRegion).period
        val advanced =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(started, P503UiEvent.ReportPeriodResult(successRead(token = 5L, period = period), currentGeneration = 3, currentCatalogVersion = 8L)),
            )
        val slot = assertNotNull(advanced.reportRegion)
        assertTrue(!slot.loading)
        assertEquals(P806SnapshotFailure.CatalogVersionMismatch, slot.failure)
        assertNotNull(slot.landed)
        val swapped =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(started, P503UiEvent.ReportPeriodResult(successRead(token = 5L, period = period), currentGeneration = 4, currentCatalogVersion = 7L)),
            )
        assertEquals(P806SnapshotFailure.Unavailable, assertNotNull(swapped.reportRegion).failure)
        // A payload from another ledger is dropped whole as an invalid state.
        val otherLedger =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(
                    started,
                    P503UiEvent.ReportPeriodResult(
                        successRead(token = 5L, period = period).copy(
                            result = P806SnapshotResult.Success(view(resolvedMonth(), ledger = otherLedgerId)),
                        ),
                        currentGeneration = 3,
                        currentCatalogVersion = 7L,
                    ),
                ),
            )
        assertEquals(P806SnapshotFailure.InvalidState, assertNotNull(otherLedger.reportRegion).failure)
    }

    // ---------------------------------------------------------------- C: preservation on read-only paths

    @Test
    fun aReadOnlyDetourKeepsThePeriodAndThePayloadAndStartsNoRead() {
        val loaded = overview(loadedSlot())
        val detour = assertIs<P503AppState.AccountFlow>(reducer.reduce(loaded, P503UiEvent.SelectAccountFlow(expenseAccountId, "餐饮 · 支出 · CNY", 0L, cny)))
        val returned = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(detour, P503UiEvent.CloseAccountFlow))
        assertSame(loaded.reportRegion, returned.reportRegion)
        val slot = assertNotNull(returned.reportRegion)
        assertTrue(!slot.loading)
        assertEquals(4L, slot.requestToken)
        assertNotNull(slot.landed)
    }

    @Test
    fun aLandingDuringAReadOnlyDetourConvergesTheCarriedSlotInsteadOfLeavingItLoading() {
        val started =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(overview(loadedSlot(token = 4L)), P503UiEvent.SelectReportPeriod(P806Period(P806PeriodKind.YEAR, year = 2025), requestToken = 5L)),
            )
        val period = assertNotNull(started.reportRegion).period
        val detour = assertIs<P503AppState.AccountFlow>(reducer.reduce(started, P503UiEvent.SelectAccountFlow(expenseAccountId, "餐饮 · 支出 · CNY", 0L, cny)))
        val landed =
            assertIs<P503AppState.AccountFlow>(
                reducer.reduce(detour, P503UiEvent.ReportPeriodResult(successRead(token = 5L, period = period), currentGeneration = 3, currentCatalogVersion = 7L)),
            )
        val carried = assertNotNull(landed.overview.reportRegion)
        assertTrue(!carried.loading)
        assertEquals(P806Period(P806PeriodKind.YEAR, year = 2025), carried.period)
        assertNotNull(carried.landed)
        // Back on the overview, nothing is left loading and no extra read is needed.
        val returned = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(landed, P503UiEvent.CloseAccountFlow))
        assertTrue(!assertNotNull(returned.reportRegion).loading)
    }

    @Test
    fun aLandingOnTheReconciliationDetailRebuildsTheNestedAccountFlowOverviewAndKeepsTheQueuePage() {
        // The queue detail's back target is the queue, and the queue's own back target is the account
        // flow: the overview that owns the report slot sits two carriers deep, so the landing writes
        // queue.returnTo.overview (the queue itself has no overview field).
        val started = overview(requestingSlot(token = 5L))
        val flow =
            P503AppState.AccountFlow(
                overview = started,
                accountId = expenseAccountId,
                balanceLine = "餐饮 · 支出 · CNY",
                balanceMinorUnits = 0L,
                balanceCurrency = cny,
            )
        val queue =
            P503AppState.ReconciliationQueue(
                returnTo = flow,
                accountId = expenseAccountId,
                accountLine = "餐饮 · 支出 · CNY",
                rows = listOf(queueRow(), queueRow(postingId = "posting-2", status = P408ReconciliationStatus.CHECKED)),
                nextCursor = null,
                totals = listOf(queueTotal()),
                pageRequestToken = 9L,
                checkedExpanded = true,
                listScroll = ListScrollPosition(2, 10),
            )
        val detail =
            P503AppState.ReconciliationDetail(
                returnTo = ReconciliationBackTarget.Queue(queue),
                anchorPostingId = PostingId("posting-1"),
                anchorEvidenceId = null,
                accountId = expenseAccountId,
                bindTarget = null,
                bindAccounts = emptyList(),
                proposals = null,
                audit = null,
            )
        val landed =
            assertIs<P503AppState.ReconciliationDetail>(
                reducer.reduce(detail, P503UiEvent.ReportPeriodResult(successRead(token = 5L), currentGeneration = 3, currentCatalogVersion = 7L)),
            )
        val landedQueue = assertIs<ReconciliationBackTarget.Queue>(landed.returnTo).state
        // The queue page the detail will return to comes back whole: rows, totals, the more-page
        // request token, the checked partition's expanded marker and the sampled scroll position
        // are all still its own (the token, marker and scroll are non-default, so they bite).
        assertSame(queue.rows, landedQueue.rows)
        assertSame(queue.totals, landedQueue.totals)
        assertEquals(9L, landedQueue.pageRequestToken)
        assertTrue(landedQueue.checkedExpanded)
        assertEquals(ListScrollPosition(2, 10), landedQueue.listScroll)
        assertEquals(1, landedQueue.uncheckedRows.size)
        assertEquals(1, landedQueue.checkedRows.size)
        // The nested account flow is the one surface that changed: its overview took the landing, so
        // detail -> queue -> flow -> overview returns to a READY report instead of a stuck loading.
        val landedFlow = landedQueue.returnTo
        assertEquals(expenseAccountId, landedFlow.accountId)
        val slot = assertNotNull(landedFlow.overview.reportRegion)
        assertTrue(!slot.loading)
        assertNotNull(slot.landed)
        assertEquals(P806ReportRegionFace.READY, p806ReportRegionFace(slot))
        // The host's last-ready memory and coordinator read that SAME landed overview through the
        // one carrier accessor, so their bookkeeping follows the landing instead of a queue field.
        assertSame(landedFlow.overview, assertNotNull(landed.carriedReportCarrier()).overview)
        // Positive control of the return path through the reducer's own Back/Close transitions:
        // detail -> queue -> flow -> overview hands back the very same instances, landed slot
        // included, and issues no read.
        val backQueue = assertIs<P503AppState.ReconciliationQueue>(reducer.reduce(landed, P503UiEvent.Back))
        assertSame(landedQueue, backQueue)
        val backFlow = assertIs<P503AppState.AccountFlow>(reducer.reduce(backQueue, P503UiEvent.Back))
        assertSame(landedFlow, backFlow)
        val backOverview = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(backFlow, P503UiEvent.CloseAccountFlow))
        assertSame(landedFlow.overview, backOverview)
        assertEquals(P806ReportRegionFace.READY, p806ReportRegionFace(assertNotNull(backOverview.reportRegion)))
        // Negative control kept: before the landing the same accessor resolves the carried original,
        // whose request is still in flight, and the landing never mutates it.
        assertSame(started, assertNotNull(detail.carriedReportCarrier()).overview)
        assertTrue(assertNotNull(started.reportRegion).loading)
    }

    @Test
    fun theMonthCardSwitchDoesNotTouchTheReportPeriodOrStartAReportRead() {
        val loaded = overview(loadedSlot(token = 4L))
        val switched =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(
                    // The month cursor really moves (the domain admits October), so this is the
                    // month-card switch its own region performs, not an absorbed out-of-domain call.
                    loaded.copy(selectableMonths = listOf(YearMonth(2026, 10), YearMonth(2026, 11))),
                    P503UiEvent.SelectMonth(YearMonth(2026, 10)),
                ),
            )
        assertEquals(YearMonth(2026, 10), switched.selectedMonth)
        assertSame(loaded.reportRegion, switched.reportRegion)
        assertEquals(4L, assertNotNull(switched.reportRegion).requestToken)
        assertTrue(!assertNotNull(switched.reportRegion).loading)
    }

    @Test
    fun theCustomDraftTextNeverChangesTheCommittedDates() {
        val custom = P806Period(P806PeriodKind.CUSTOM, customStart = LocalDate(2026, 11, 1), customEnd = LocalDate(2026, 11, 16))
        val started =
            assertIs<P503AppState.OverviewEmpty>(reducer.reduce(overview(loadedSlot()), P503UiEvent.SelectReportPeriod(custom, requestToken = 5L)))
        val edited =
            assertIs<P503AppState.OverviewEmpty>(reducer.reduce(started, P503UiEvent.UpdateReportCustomRange("2026-12-01", "2026-12-31")))
        val slot = assertNotNull(edited.reportRegion)
        assertEquals("2026-12-01", slot.customStartText)
        assertEquals("2026-12-31", slot.customEndText)
        // The committed period and the in-flight request are untouched: editing text is not a read.
        assertEquals(custom, slot.period)
        assertEquals(5L, slot.requestToken)
        assertTrue(slot.loading)
    }

    // ---------------------------------------------------------------- invalidation on the authoritative refresh

    @Test
    fun theAuthoritativeRefreshInvalidatesTheOldRequestAndTheHostReopensTheRegion() {
        val stale =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(overview(loadedSlot(token = 4L)), P503UiEvent.SelectReportPeriod(P806Period(P806PeriodKind.YEAR, year = 2025), requestToken = 5L)),
            )
        val period = assertNotNull(stale.reportRegion).period
        // The refreshed overview carries NO report slot: nothing of the old request, payload or
        // failure survives it, so the old first screen can never masquerade as the post-refresh one.
        val refreshed =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(P503AppState.Created, P503UiEvent.RefreshResult(currentState = ledger())),
            )
        assertNull(refreshed.reportRegion)
        // The host re-opens the region on a wired graph: an explicit NOT_LOADED slot appears.
        val reopened = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(refreshed, P503UiEvent.OpenReportRegion()))
        val slot = assertNotNull(reopened.reportRegion)
        assertTrue(!slot.loading)
        assertNull(slot.landed)
        assertNull(slot.failure)
        assertEquals(0L, slot.requestToken)
        assertEquals(P806ReportRegionFace.NOT_LOADED, p806ReportRegionFace(slot))
        // Opening again never resets an existing slot (no read is lost behind it).
        assertSame(reopened, reducer.reduce(reopened, P503UiEvent.OpenReportRegion()))
        // The old read cannot land on the refreshed/re-opened overview: no request is in flight.
        assertSame(reopened, reducer.reduce(reopened, P503UiEvent.ReportPeriodResult(successRead(token = 5L, period = period), currentGeneration = 3, currentCatalogVersion = 7L)))
    }

    @Test
    fun theExplicitPostWriteRereadBypassesTheSamePeriodDedupAndKeepsTheOldPayload() {
        // The refreshed page keeps the last READY (anchored) period: an ordinary selection of that
        // very period is a no-op, which is exactly why the post-write path needs its own event.
        val loaded = overview(loadedSlot(token = 4L))
        val month = assertNotNull(loaded.reportRegion).period
        assertSame(loaded, reducer.reduce(loaded, P503UiEvent.SelectReportPeriod(month, requestToken = 5L)))
        // The explicit re-read starts even for the same period, with a newer token.
        val reread =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(loaded, P503UiEvent.RetryReportPeriod(requestToken = 5L, period = month)),
            )
        val slot = assertNotNull(reread.reportRegion)
        assertTrue(slot.loading)
        assertEquals(5L, slot.requestToken)
        assertEquals(month, slot.period)
        assertNull(slot.failure)
        // The old payload stays visible as explicitly old context (its own kind and as-of kept).
        assertNotNull(slot.landed)
        assertEquals(P806PeriodKind.MONTH, slot.landedKind)
        assertEquals(readInstant, slot.landedAsOf)
        // A token that does not grow is a stale host call: nothing restarts.
        assertSame(reread, reducer.reduce(reread, P503UiEvent.RetryReportPeriod(requestToken = 5L, period = month)))
    }

    @Test
    fun theHostReopensTheRegionWithOnlyAnEligibleSlotAndNothingOfItsRequestIdentity() {
        val accepted =
            P806ReportRegionState(
                period = P806Period(P806PeriodKind.MONTH),
                kind = P806PeriodKind.MONTH,
                customStartText = "2026-11-01",
                customEndText = "2026-11-16",
                requestToken = 9L,
                loading = false,
                landed = view(resolvedMonth()),
                landedKind = P806PeriodKind.MONTH,
                landedAsOf = readInstant,
                landedGeneration = 3,
                landedCatalogVersion = 7L,
            )
        val reopened =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(overview(null), P503UiEvent.OpenReportRegion(previous = accepted)),
            )
        val slot = assertNotNull(reopened.reportRegion)
        // The display fields come across; NO request identity, loading marker or failure does.
        assertEquals(P806Period(P806PeriodKind.MONTH), slot.period)
        assertEquals(P806PeriodKind.MONTH, slot.kind)
        assertEquals("2026-11-01", slot.customStartText)
        assertEquals("2026-11-16", slot.customEndText)
        assertEquals(accepted.landed, slot.landed)
        assertEquals(P806PeriodKind.MONTH, slot.landedKind)
        assertEquals(readInstant, slot.landedAsOf)
        assertEquals(3, slot.landedGeneration)
        assertEquals(7L, slot.landedCatalogVersion)
        assertEquals(0L, slot.requestToken)
        assertTrue(!slot.loading)
        assertNull(slot.failure)
        assertEquals(P806ReportRegionFace.READY, p806ReportRegionFace(slot))
        // A slot whose last request FAILED (an old payload kept as labelled context) is never
        // imported as current: the rebuilt page starts NOT_LOADED and reads again instead of
        // presenting the old amount as READY (D-1).
        val failedSlot =
            accepted.copy(
                requestToken = 10L,
                failure = P806SnapshotFailure.Unavailable,
            )
        val afterFailure =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(overview(null), P503UiEvent.OpenReportRegion(previous = failedSlot)),
            )
        assertEquals(P806ReportRegionState(), assertNotNull(afterFailure.reportRegion))
        // A slot a determinate write still owns (write-stale with a payload) is the same graph's
        // HISTORY: it is imported as labelled old data for the owed re-read — never READY, and with
        // no request identity of its own.
        val staleSlot = accepted.copy(requestToken = 11L, loading = true, writeStale = true)
        val afterWrite =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(overview(null), P503UiEvent.OpenReportRegion(previous = staleSlot)),
            )
        val history = assertNotNull(afterWrite.reportRegion)
        assertEquals(accepted.landed, history.landed)
        assertEquals(P806PeriodKind.MONTH, history.landedKind)
        assertEquals(readInstant, history.landedAsOf)
        assertTrue(history.writeStale)
        assertTrue(!history.loading)
        assertEquals(0L, history.requestToken)
        assertNull(history.failure)
        assertTrue(!p806ReportSlotIsEligible(history))
        assertEquals(P806ReportRegionFace.FAILED, p806ReportRegionFace(history))
        assertNotNull(p806ReportLastSuccessContextText(history))
        // A payload from ANOTHER ledger is never seeded: the page starts empty instead of mixing.
        val foreign =
            accepted.copy(
                landed =
                    P806ReportView(
                        ledgerId = otherLedgerId,
                        period = resolvedMonth(),
                        currencies = emptyList(),
                        expenseCategories = emptyList(),
                        incomeCategories = emptyList(),
                        uncategorizedExpenseTotals = emptyList(),
                        uncategorizedIncomeTotals = emptyList(),
                        transactionCount = 0,
                        futureTransactionCount = 0,
                        detailPage = P806TransactionPage(rows = emptyList(), nextCursor = null),
                        detailCount = 0,
                    ),
            )
        val foreignPage =
            assertIs<P503AppState.OverviewEmpty>(reducer.reduce(overview(null), P503UiEvent.OpenReportRegion(previous = foreign)))
        assertNull(assertNotNull(foreignPage.reportRegion).landed)
        // No previous slot at all: the plain default slot.
        val plain = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(overview(null), P503UiEvent.OpenReportRegion()))
        assertEquals(P806ReportRegionState(), assertNotNull(plain.reportRegion))
    }

    @Test
    fun openingTheCustomInputsOnlyRevealsTheRangeAndNeverStartsARead() {
        val loaded = overview(loadedSlot(token = 4L))
        val opened =
            assertIs<P503AppState.OverviewEmpty>(reducer.reduce(loaded, P503UiEvent.OpenReportCustomInputs))
        val slot = assertNotNull(opened.reportRegion)
        // Only the VISIBLE selection moves: the committed period, the request identity and the
        // payload are untouched, and nothing is in flight — an untouched range never reads.
        assertEquals(P806PeriodKind.CUSTOM, slot.kind)
        assertEquals(P806Period(P806PeriodKind.MONTH), slot.period)
        assertEquals(4L, slot.requestToken)
        assertTrue(!slot.loading)
        assertNotNull(slot.landed)
        // Re-opening is absorbed (the same page instance).
        assertSame(opened, reducer.reduce(opened, P503UiEvent.OpenReportCustomInputs))
    }

    @Test
    fun anUnwiredOverviewAbsorbsEveryReportIntentAndOnlyTheOpenEventCreatesTheSlot() {
        val unwired = overview(null)
        assertSame(unwired, reducer.reduce(unwired, P503UiEvent.SelectReportPeriod(P806Period(P806PeriodKind.WEEK), requestToken = 1L)))
        assertSame(unwired, reducer.reduce(unwired, P503UiEvent.RetryReportPeriod(requestToken = 1L)))
        assertSame(unwired, reducer.reduce(unwired, P503UiEvent.UpdateReportCustomRange("x", "y")))
        assertSame(unwired, reducer.reduce(unwired, P503UiEvent.ReportPeriodResult(successRead(token = 1L), currentGeneration = 3, currentCatalogVersion = 7L)))
        // The host's open event is the ONE way a slot exists; without it no intent can start a read.
        val opened = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(unwired, P503UiEvent.OpenReportRegion()))
        assertEquals(P806ReportRegionFace.NOT_LOADED, p806ReportRegionFace(assertNotNull(opened.reportRegion)))
        // The same events are absorbed outside the overview too (a late dispatch never ISEs).
        val ready = P503AppState.Ready()
        assertSame(ready, reducer.reduce(ready, P503UiEvent.OpenReportRegion()))
        assertSame(ready, reducer.reduce(ready, P503UiEvent.SelectReportPeriod(P806Period(P806PeriodKind.WEEK), requestToken = 1L)))
        val transient = P503AppState.Created
        assertSame(transient, reducer.reduce(transient, P503UiEvent.RetryReportPeriod(requestToken = 1L)))
    }

    /**
     * N-2 (承重) interleaving regression, the exact hostile order: a LIVE or CARRIED slot has a request
     * in flight with token N and a prior success; the determinate write invalidates; the OLD
     * successful result arrives BEFORE the RefreshFailed; then RetryRefresh produces an eligible page.
     * The old result must be fenced (no landing, no READY, no re-seed), only a token above the fence
     * may reach READY, and the failed refresh must conclude the pending slot as a NAMED failure with
     * a retry entry instead of leaving it in a write-stale fake loading.
     *
     * ABLATION (承重): drop the `fenceToken` check in [p806ReportLandingDecision], the `writeStale`
     * mark in the invalidation or the post-refresh conclusion in [P503ReducerImpl.reduce] and the
     * "old result cannot land" / "no re-seed" / "never a stuck loading" assertions below go RED.
     */
    @Test
    fun theWriteInvalidationFencesTheInFlightRequestOnTheLiveAndCarriedSlot() {
        val memory = P806ReportSlotMemory()
        // A live in-flight request (token 9) over an older success.
        val inFlight = P806ReportRegionState(period = P806Period(P806PeriodKind.MONTH), kind = P806PeriodKind.MONTH, requestToken = 9L, loading = true, landed = view(resolvedMonth()), landedKind = P806PeriodKind.MONTH, landedAsOf = readInstant, landedGeneration = 3, landedCatalogVersion = 7L)
        memory.clear()
        val before = overview(inFlight)
        // The determinate write invalidates on the main hop: fence = 9, the old request ends, and the
        // owed authoritative re-read keeps a REAL pending marker over the old payload.
        val fenced = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(before, P503UiEvent.InvalidateReportRegion))
        val fencedSlot = assertNotNull(fenced.reportRegion)
        assertEquals(9L, fencedSlot.fenceToken)
        assertTrue(fencedSlot.loading)
        assertTrue(fencedSlot.writeStale)
        assertEquals(P806ReportRegionFace.LOADING, p806ReportRegionFace(fencedSlot))
        assertTrue(!p806ReportSlotIsEligible(fencedSlot))
        // The OLD successful result (token 9) arrives before the refresh's own outcome: fenced, no change.
        assertSame(fenced, reducer.reduce(fenced, P503UiEvent.ReportPeriodResult(successRead(token = 9L), currentGeneration = 3, currentCatalogVersion = 7L)))
        // A fenced in-flight marker is not a concluded landing: the memory records nothing, so it
        // cannot re-seed the old payload as READY (the write's history mark is exercised by
        // [aFailedRefreshRetryReseedsThePreWriteSlotOnlyAsOldHistoryAndANewReadReachesReady]).
        memory.record(fenced)
        assertNull(memory.previousFor(3, 7L))
        // The refresh FAILED on the live overview: the existing READ-failure page takes over (the slot
        // does not survive it as a write-stale fake loading), and the retry's eligible page reads
        // again through the host's retained debt (P503HostCoordinatorTest).
        assertIs<P503AppState.InfrastructureFailure>(reducer.reduce(fenced, P503UiEvent.RefreshFailed))
        // The retry's valid new read (token 10 > fence) is the only path back to READY.
        val requesting = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(fenced, P503UiEvent.RetryReportPeriod(requestToken = 10L, period = P806Period(P806PeriodKind.MONTH))))
        assertTrue(assertNotNull(requesting.reportRegion).loading)
        assertTrue(assertNotNull(requesting.reportRegion).writeStale)
        val landed = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(requesting, P503UiEvent.ReportPeriodResult(successRead(token = 10L), currentGeneration = 3, currentCatalogVersion = 7L)))
        val landedSlot = assertNotNull(landed.reportRegion)
        assertTrue(!landedSlot.writeStale)
        assertEquals(P806ReportRegionFace.READY, p806ReportRegionFace(landedSlot))
        memory.record(landed)
        assertNotNull(memory.previousFor(3, 7L))
        // The CARRIED variant: the same fence and conclusion reach the preserved overview of a detour
        // surface that ABSORBS the refresh family — the region must not stay pending there either.
        val carried =
            assertIs<P503AppState.AccountFlow>(
                reducer.reduce(
                    P503AppState.AccountFlow(
                        overview = overview(P806ReportRegionState(period = P806Period(P806PeriodKind.MONTH), kind = P806PeriodKind.MONTH, requestToken = 11L, loading = true, landed = view(resolvedMonth()), landedKind = P806PeriodKind.MONTH, landedGeneration = 3, landedCatalogVersion = 7L)),
                        accountId = expenseAccountId,
                        balanceLine = "餐饮 · 支出 · CNY",
                        balanceMinorUnits = 0L,
                        balanceCurrency = cny,
                    ),
                    P503UiEvent.InvalidateReportRegion,
                ),
            )
        val carriedSlot = assertNotNull(carried.overview.reportRegion)
        assertEquals(11L, carriedSlot.fenceToken)
        assertTrue(carriedSlot.loading)
        assertTrue(carriedSlot.writeStale)
        // Its old result is fenced there too, and the carried state records nothing eligible.
        val carriedOld =
            assertIs<P503AppState.AccountFlow>(
                reducer.reduce(carried, P503UiEvent.ReportPeriodResult(successRead(token = 11L), currentGeneration = 3, currentCatalogVersion = 7L)),
            )
        val carriedOldSlot = assertNotNull(carriedOld.overview.reportRegion)
        assertEquals(carriedSlot, carriedOldSlot)
        assertTrue(!p806ReportSlotIsEligible(carriedOldSlot))
        memory.record(carriedOld)
        assertNull(memory.previousFor(3, 7L))
        val carriedFailed = reducer.reduce(carried, P503UiEvent.RefreshFailed)
        val carriedFailedSlot = assertNotNull(assertIs<P503AppState.AccountFlow>(carriedFailed).overview.reportRegion)
        assertTrue(!carriedFailedSlot.loading)
        assertEquals(P806SnapshotFailure.Unavailable, carriedFailedSlot.failure)
        assertEquals(P806ReportRegionFace.FAILED, p806ReportRegionFace(carriedFailedSlot))
        // An unwired landing drops the memory whole: nothing is re-seeded.
        val unwiredPage =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(
                    overview(P806ReportRegionState(requestToken = 5L, loading = true)),
                    P503UiEvent.ReportPeriodResult(
                        read = P806ReportRead.Unwired(P806ReportRequestIdentity(5L, ledgerId, P806Period(P806PeriodKind.MONTH)), generation = 3),
                        currentGeneration = 3,
                        currentCatalogVersion = 7L,
                    ),
                ),
            )
        memory.record(unwiredPage)
        assertNull(memory.previousFor(3, 7L))
    }

    /**
     * N-2 (承重): a determinate write demotes the remembered payload to HISTORY at the WRITE START;
     * the refresh FAILS; the user's RetryRefresh lands a fresh page. The pre-write payload must never
     * be re-seeded as READY — it comes back as labelled old data with the failure face — and only the
     * first LEGAL new read reaches READY again.
     *
     * ABLATION (承重): clear the history mark in [P806ReportSlotMemory.markWriteStale] and step 2/4
     * re-seeds the old value as READY; drop the write-stale import and the rebuilt page forgets the
     * same graph's last success.
     */
    @Test
    fun aFailedRefreshRetryReseedsThePreWriteSlotOnlyAsOldHistoryAndANewReadReachesReady() {
        val memory = P806ReportSlotMemory()
        // 1. READY: one accepted landing is remembered.
        val ready =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(overview(requestingSlot(token = 5L)), P503UiEvent.ReportPeriodResult(successRead(token = 5L), currentGeneration = 3, currentCatalogVersion = 7L)),
            )
        memory.record(ready)
        assertNotNull(memory.previousFor(3, 7L))
        assertEquals(P806ReportRegionFace.READY, p806ReportRegionFace(assertNotNull(ready.reportRegion)))
        // 2. Determinate write: the host demotes the memory to history before the refresh's own read.
        memory.markWriteStale()
        val seededHistory = assertNotNull(memory.previousFor(3, 7L))
        assertTrue(seededHistory.writeStale)
        assertTrue(!p806ReportSlotIsEligible(seededHistory))
        assertEquals(P806ReportRegionFace.FAILED, p806ReportRegionFace(seededHistory))
        // 3. That refresh FAILED; the retry lands a fresh page that carries no slot.
        val refreshed =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(P503AppState.Created, P503UiEvent.RefreshResult(currentState = ledger())),
            )
        assertNull(refreshed.reportRegion)
        // 4. The rebuilt page re-seeds the same graph's last success as labelled old history only.
        val reopened =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(refreshed, P503UiEvent.OpenReportRegion(previous = memory.previousFor(3, 7L))),
            )
        val reopenedSlot = assertNotNull(reopened.reportRegion)
        assertEquals(P806ReportRegionFace.FAILED, p806ReportRegionFace(reopenedSlot))
        assertTrue(reopenedSlot.writeStale)
        assertNotNull(reopenedSlot.landed)
        assertNotNull(p806ReportLastSuccessContextText(reopenedSlot))
        // 5. The FIRST legal new read is the only path back to READY.
        val requesting =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(reopened, P503UiEvent.RetryReportPeriod(requestToken = 6L, period = P806Period(P806PeriodKind.MONTH))),
            )
        assertTrue(assertNotNull(requesting.reportRegion).loading)
        val landedAgain =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(requesting, P503UiEvent.ReportPeriodResult(successRead(token = 6L), currentGeneration = 3, currentCatalogVersion = 7L)),
            )
        memory.record(landedAgain)
        assertNotNull(memory.previousFor(3, 7L))
        assertEquals(P806ReportRegionFace.READY, p806ReportRegionFace(assertNotNull(landedAgain.reportRegion)))
    }

    /**
     * ① of the lifecycle contract (承重): a loaded amount, a determinate write, and then the owed
     * re-read FAILS. The old amount keeps its REAL provenance (period, currency axis, as-of) as
     * labelled history, the failure is retryable, a rebuilt page is NOT READY, and only the CURRENT
     * request's success restores READY.
     *
     * ABLATION (承重): make [p806ReportSlotIsEligible] ignore `failure` (D-1) and the rebuilt page
     * re-seeds the old amount as READY; clear the memory at the write start (no history) and the
     * rebuilt page forgets the same graph's last success.
     */
    @Test
    fun aFailedPostWriteReadKeepsTheOldAmountWithItsProvenanceAndOnlyASuccessRestoresReady() {
        val memory = P806ReportSlotMemory()
        // 1. READY: one accepted landing is remembered with its real context.
        val ready =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(overview(requestingSlot(token = 5L)), P503UiEvent.ReportPeriodResult(successRead(token = 5L), currentGeneration = 3, currentCatalogVersion = 7L)),
            )
        memory.record(ready)
        assertNotNull(memory.previousFor(3, 7L))
        assertEquals(P806ReportRegionFace.READY, p806ReportRegionFace(assertNotNull(ready.reportRegion)))
        // 2. The determinate write fences the slot at the write start; the host demotes its memory to
        // history (the payload stays, its READY eligibility does not).
        memory.markWriteStale()
        val historyAtWrite = assertNotNull(memory.previousFor(3, 7L))
        assertTrue(historyAtWrite.writeStale)
        assertTrue(!p806ReportSlotIsEligible(historyAtWrite))
        val fenced = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(ready, P503UiEvent.InvalidateReportRegion))
        assertTrue(assertNotNull(fenced.reportRegion).writeStale)
        // 3. The owed re-read is accepted (its token is above the fence) and FAILS.
        val reread = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(fenced, P503UiEvent.RetryReportPeriod(requestToken = 6L, period = P806Period(P806PeriodKind.MONTH))))
        val failed = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(reread, P503UiEvent.ReportPeriodResult(failedRead(token = 6L), currentGeneration = 3, currentCatalogVersion = 7L)))
        val slot = assertNotNull(failed.reportRegion)
        assertEquals(P806SnapshotFailure.Unavailable, slot.failure)
        assertTrue(!slot.loading)
        assertTrue(!slot.writeStale)
        assertEquals(P806ReportRegionFace.FAILED, p806ReportRegionFace(slot))
        // The old amount stays visible WITH its provenance: its own period, axis, read instant, kind.
        val old = assertNotNull(slot.landed)
        assertEquals(resolvedMonth(), old.period)
        assertEquals(listOf(cny), old.currencies.map { row -> row.currency })
        assertEquals(readInstant, slot.landedAsOf)
        assertEquals(P806PeriodKind.MONTH, slot.landedKind)
        assertEquals("最近成功（期间 月：2026-11-01 至 2026-11-30；币种轴 CNY；读取时点 2026-11-18T02:00:00Z）", p806ReportLastSuccessContextText(slot))
        // The failure is retryable: the face offers the explicit retry, which reads the same period.
        val retried = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(failed, P503UiEvent.RetryReportPeriod(requestToken = 7L)))
        assertTrue(assertNotNull(retried.reportRegion).loading)
        // 4. It is NOT a re-seed source for READY, but the same graph's last success stays labelled
        // old history: a rebuilt page shows the failure face with the old payload and its retry.
        assertTrue(!p806ReportSlotIsEligible(slot))
        memory.record(failed)
        val seeded = assertNotNull(memory.previousFor(3, 7L))
        assertTrue(seeded.writeStale)
        assertTrue(!p806ReportSlotIsEligible(seeded))
        val rebuilt = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(P503AppState.Created, P503UiEvent.RefreshResult(currentState = ledger())))
        val reopened = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(rebuilt, P503UiEvent.OpenReportRegion(previous = seeded)))
        val reopenedSlot = assertNotNull(reopened.reportRegion)
        assertEquals(old, reopenedSlot.landed)
        assertEquals(P806ReportRegionFace.FAILED, p806ReportRegionFace(reopenedSlot))
        // 5. Only the CURRENT request's success restores READY (the explicit retry is that request).
        val current = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(reopened, P503UiEvent.RetryReportPeriod(requestToken = 8L)))
        val landed = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(current, P503UiEvent.ReportPeriodResult(successRead(token = 8L), currentGeneration = 3, currentCatalogVersion = 7L)))
        assertEquals(P806ReportRegionFace.READY, p806ReportRegionFace(assertNotNull(landed.reportRegion)))
    }

    /**
     * ① negative sample (承重): the SAME order with a REFUSED lease (the runtime refused before any
     * read ran). The refusal ends only the matching request: it becomes a typed failure with the old
     * amount beside it, no generation is claimed and nothing becomes READY.
     *
     * ABLATION (承重): treat [P806ReportRead.Refused] as a success or let it keep `landed` current and
     * the `!eligible` / `FAILED` assertions go RED.
     */
    @Test
    fun aRefusedPostWriteReadIsATypedFailureAndNeverTheCurrentResult() {
        val ready = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(overview(requestingSlot(token = 5L)), P503UiEvent.ReportPeriodResult(successRead(token = 5L), currentGeneration = 3, currentCatalogVersion = 7L)))
        val fenced = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(ready, P503UiEvent.InvalidateReportRegion))
        val reread = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(fenced, P503UiEvent.RetryReportPeriod(requestToken = 6L, period = P806Period(P806PeriodKind.MONTH))))
        val refused =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(
                    reread,
                    P503UiEvent.ReportPeriodResult(
                        read = P806ReportRead.Refused(P806ReportRequestIdentity(token = 6L, ledgerId = ledgerId, period = P806Period(P806PeriodKind.MONTH))),
                        currentGeneration = 3,
                        currentCatalogVersion = 7L,
                    ),
                ),
            )
        val slot = assertNotNull(refused.reportRegion)
        assertEquals(P806SnapshotFailure.Unavailable, slot.failure)
        assertEquals(P806ReportRegionFace.FAILED, p806ReportRegionFace(slot))
        assertNotNull(slot.landed)
        assertTrue(!p806ReportSlotIsEligible(slot))
    }

    /**
     * Negative control (承重): a duplicate explicit retry of the period already in flight coalesces
     * into that ONE request, while a REAL period change preempts it. The post-write re-read is still
     * accepted because the invalidation's pending marker is not a live request.
     *
     * ABLATION (承重): drop the `inFlight` coalescing in [P503ReducerImpl]'s retry transition and a
     * second token starts a second read (the "same instance" assertion goes RED); drop the
     * `requestToken > fenceToken` qualifier and the post-write re-read is absorbed instead.
     */
    @Test
    fun aDuplicateRetryOfThePeriodInFlightCoalescesIntoTheActiveRequest() {
        val requesting = overview(requestingSlot(token = 10L, period = P806Period(P806PeriodKind.WEEK, weekStart = LocalDate(2026, 11, 16)), landed = view(resolvedMonth()), fenceToken = 9L))
        val week = P806Period(P806PeriodKind.WEEK, weekStart = LocalDate(2026, 11, 16))
        // The same period again (a double tap / a recomposition-driven retry): absorbed, no new read.
        assertSame(requesting, reducer.reduce(requesting, P503UiEvent.RetryReportPeriod(requestToken = 11L, period = week)))
        assertSame(requesting, reducer.reduce(requesting, P503UiEvent.RetryReportPeriod(requestToken = 11L)))
        // A genuinely different period preempts the read in flight.
        val preempted = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(requesting, P503UiEvent.RetryReportPeriod(requestToken = 11L, period = P806Period(P806PeriodKind.YEAR, year = 2025))))
        assertEquals(11L, assertNotNull(preempted.reportRegion).requestToken)
        assertEquals(P806Period(P806PeriodKind.YEAR, year = 2025), assertNotNull(preempted.reportRegion).period)
        // The write's owed re-read sits at the fence (no live request yet), so it is never absorbed.
        val fenced = overview(P806ReportRegionState(period = P806Period(P806PeriodKind.MONTH), kind = P806PeriodKind.MONTH, requestToken = 9L, fenceToken = 9L, loading = true, writeStale = true, landed = view(resolvedMonth()), landedKind = P806PeriodKind.MONTH))
        val reread = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(fenced, P503UiEvent.RetryReportPeriod(requestToken = 10L, period = P806Period(P806PeriodKind.MONTH))))
        assertEquals(10L, assertNotNull(reread.reportRegion).requestToken)
    }

    /**
     * Negative controls (承重): another ledger, an advanced generation and an advanced catalog never
     * land, never re-seed and never become READY; an OLD token never clears a newer request.
     *
     * ABLATION (承重): remove any of the ledger/generation/catalog/token checks in
     * [p806ReportLandingDecision] and the corresponding assertion below goes RED.
     */
    @Test
    fun anotherLedgerGenerationCatalogOrOldTokenNeverMakesTheSlotCurrent() {
        val base = overview(requestingSlot(token = 5L))
        val otherLedgerPage = overview(requestingSlot(token = 5L), ledger = otherLedgerId)
        // Another ledger's request: a no-op on this page's slot.
        assertSame(otherLedgerPage, reducer.reduce(otherLedgerPage, P503UiEvent.ReportPeriodResult(successRead(token = 5L), currentGeneration = 3, currentCatalogVersion = 7L)))
        // An advanced generation ends the request as a typed failure and never as READY.
        val advanced = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(base, P503UiEvent.ReportPeriodResult(successRead(token = 5L), currentGeneration = 4, currentCatalogVersion = 7L)))
        assertEquals(P806SnapshotFailure.Unavailable, assertNotNull(advanced.reportRegion).failure)
        assertEquals(P806ReportRegionFace.FAILED, p806ReportRegionFace(assertNotNull(advanced.reportRegion)))
        val memory = P806ReportSlotMemory()
        memory.record(advanced)
        assertNull(memory.previousFor(4, 7L))
        // An advanced catalog ends it as the catalog failure and never as READY.
        val catalogAdvanced = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(base, P503UiEvent.ReportPeriodResult(successRead(token = 5L), currentGeneration = 3, currentCatalogVersion = 8L)))
        assertEquals(P806SnapshotFailure.CatalogVersionMismatch, assertNotNull(catalogAdvanced.reportRegion).failure)
        assertEquals(P806ReportRegionFace.FAILED, p806ReportRegionFace(assertNotNull(catalogAdvanced.reportRegion)))
        // An old token never clears the newer request's loading.
        val newer = overview(requestingSlot(token = 6L))
        assertSame(newer, reducer.reduce(newer, P503UiEvent.ReportPeriodResult(successRead(token = 5L), currentGeneration = 3, currentCatalogVersion = 7L)))
        assertTrue(assertNotNull(newer.reportRegion).loading)
    }

    /**
     * R-3 (承重): a refresh failure concludes only the OUTER authoritative-refresh wait (the
     * invalidation marker at the fence). A report read ACCEPTED after the fence is the region's own
     * request: the refresh failure must not end it, and its landing still applies.
     *
     * ABLATION (承重): drop the `requestToken <= fenceToken` qualifier in
     * [P503ReducerImpl.concludeReportWriteStale] and the in-flight request is ended (the "still
     * loading"/"READY after landing" assertions go RED).
     */
    @Test
    fun anUnrelatedRefreshFailureDoesNotEndAnAcceptedReportRequestOnACarrier() {
        val accepted =
            P806ReportRegionState(
                period = P806Period(P806PeriodKind.MONTH),
                kind = P806PeriodKind.MONTH,
                requestToken = 10L,
                fenceToken = 9L,
                loading = true,
                writeStale = true,
                landed = view(resolvedMonth()),
                landedKind = P806PeriodKind.MONTH,
            )
        val carried =
            P503AppState.AccountFlow(
                overview = overview(accepted),
                accountId = expenseAccountId,
                balanceLine = "餐饮 · 支出 · CNY",
                balanceMinorUnits = 0L,
                balanceCurrency = cny,
            )
        val afterFailure = assertIs<P503AppState.AccountFlow>(reducer.reduce(carried, P503UiEvent.RefreshFailed))
        val slot = assertNotNull(afterFailure.overview.reportRegion)
        assertTrue(slot.loading)
        assertTrue(slot.writeStale)
        assertNull(slot.failure)
        // Its own landing still applies and becomes the current result.
        val landed =
            assertIs<P503AppState.AccountFlow>(
                reducer.reduce(afterFailure, P503UiEvent.ReportPeriodResult(successRead(token = 10L), currentGeneration = 3, currentCatalogVersion = 7L)),
            )
        val landedSlot = assertNotNull(landed.overview.reportRegion)
        assertTrue(!landedSlot.loading)
        assertTrue(!landedSlot.writeStale)
        assertEquals(P806ReportRegionFace.READY, p806ReportRegionFace(landedSlot))
    }

    /** The host's ONE switch landing order ([landRestoreSwitchResult]) with the production reducer. */
    private fun landSwitchThroughTheHostOrder(
        state: P503AppState,
        result: BackupRestoreSwitchResult,
        preSwitchGeneration: Generation,
    ): P503AppState {
        var current = state
        return landRestoreSwitchResult(
            result = result,
            preSwitchGeneration = preSwitchGeneration,
            dispatch = { event ->
                current = reducer.reduce(current, event)
                current
            },
            retireHostReportLifecycle = {},
        )
    }

    /**
     * R-1/F-1 (承重): the switch result's authoritative landingGeneration routes the retirement — never
     * the variant alone and never the ledger id. A RolledBack that reopened the graph advanced the
     * generation, so the slot (payload or not) is dropped before the preserved overview can show it; a
     * pre-quiesce rejection whose landingGeneration is the untouched generation keeps it.
     *
     * ABLATION (承重): retire only for `Committed`, or compare ledger ids instead of generations, and
     * the RolledBack case rides the old slot into the reopened graph (the `null` assertions go RED);
     * drop the generation comparison and the same-generation rejection loses its slot.
     */
    @Test
    fun aReopenedGenerationRetiresTheOldSlotAndASameGenerationRejectionKeepsIt() {
        fun backupRestore(): P503AppState.BackupRestore =
            P503AppState.BackupRestore(
                overview = P503AppState.OverviewEmpty(state = ledger(), selectedTab = P503Tab.ANALYSIS, reportRegion = loadedSlot()),
                runningConfirm = true,
                token = RestorePreflightToken("tok", 1, "ledger-local-test", ByteArray(32), null),
            )
        // RolledBack: the post-publish rollback reopened the OLD graph with a NEW generation.
        val rolledBack = landSwitchThroughTheHostOrder(backupRestore(), BackupRestoreSwitchResult.RolledBack(runtimeGeneration = 4), preSwitchGeneration = 3)
        val rolledBackState = assertIs<P503AppState.BackupRestore>(rolledBack)
        assertNull(rolledBackState.overview.reportRegion)
        // Closing the surface returns the preserved overview without the old graph's slot.
        val closed = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(rolledBackState, P503UiEvent.CloseBackupRestore))
        assertNull(closed.reportRegion)
        // A pre-quiesce rejection carries the untouched generation: the same slot is preserved.
        val stale =
            landSwitchThroughTheHostOrder(
                backupRestore(),
                BackupRestoreSwitchResult.Stale(
                    reason = BackupRestoreStaleReason.StagingArtifactMissing,
                    runtime = BackupRestoreRuntimeOutcome.RestoredReady,
                    runtimeGeneration = 3,
                ),
                preSwitchGeneration = 3,
            )
        val staleState = assertIs<P503AppState.BackupRestore>(stale)
        assertEquals(loadedSlot(), staleState.overview.reportRegion)
    }

    /**
     * F-1 (承重): a payload-less FAILED slot and an in-flight/NOT_LOADED slot are retired by an
     * ACCEPTED switch too — the payload's own generation cannot prove the graph did not change, so
     * the host's authoritative predicate drives the retirement. The new graph's page is then due and
     * reads its own default month, and a late old result can never touch the new request token.
     *
     * ABLATION (承重): compare the payload's landedGeneration (the F blocker) and both payload-less
     * cases keep the old slot (the `null`/due assertions go RED); retire unconditionally and the
     * same-generation rejection in the test above loses its slot.
     */
    @Test
    fun anAcceptedSwitchRetiresPayloadLessFailedAndInFlightSlotsWithoutTouchingTheNewToken() {
        fun backup(slot: P806ReportRegionState): P503AppState.BackupRestore =
            P503AppState.BackupRestore(
                overview = P503AppState.OverviewEmpty(state = ledger(), selectedTab = P503Tab.ANALYSIS, reportRegion = slot),
                runningConfirm = true,
                token = RestorePreflightToken("tok", 1, "ledger-local-test", ByteArray(32), null),
            )

        fun year2019(): P806Period = P806Period(P806PeriodKind.YEAR, year = 2019)
        // (a) A payload-less FAILED slot (the 2019 year read failed): it must not survive the switch.
        val failed = P806ReportRegionState(period = year2019(), kind = P806PeriodKind.YEAR, requestToken = 5L, fenceToken = 5L, failure = P806SnapshotFailure.Unavailable)
        val afterFailed = landSwitchThroughTheHostOrder(backup(failed), BackupRestoreSwitchResult.RolledBack(runtimeGeneration = 4), preSwitchGeneration = 3)
        val closedFailed = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(assertIs<P503AppState.BackupRestore>(afterFailed), P503UiEvent.CloseBackupRestore))
        assertNull(closedFailed.reportRegion)
        // The new graph's page is due again and reads the DEFAULT month (the old failure no longer blocks it).
        assertTrue(p806ReportInitialReadDue(closedFailed))
        // (b) An in-flight/NOT_LOADED slot is retired as well (an old token could never conclude it).
        val inFlight = P806ReportRegionState(period = year2019(), kind = P806PeriodKind.YEAR, requestToken = 5L, loading = true)
        val returned = assertIs<P503AppState.OverviewEmpty>(landSwitchThroughTheHostOrder(backup(inFlight), BackupRestoreSwitchResult.Committed(runtimeGeneration = 4), preSwitchGeneration = 3))
        assertNull(returned.reportRegion)
        // (c) The fresh page opens a new request; a late OLD result cannot disturb it.
        val opened = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(returned, P503UiEvent.OpenReportRegion()))
        val requesting = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(opened, P503UiEvent.SelectReportPeriod(P806Period(P806PeriodKind.MONTH), requestToken = 6L)))
        val untouched =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(requesting, P503UiEvent.ReportPeriodResult(successRead(token = 5L, period = year2019()), currentGeneration = 4, currentCatalogVersion = 7L)),
            )
        val slot = assertNotNull(untouched.reportRegion)
        assertTrue(slot.loading)
        assertEquals(6L, slot.requestToken)
        assertEquals(P806Period(P806PeriodKind.MONTH), slot.period)
        assertNull(slot.failure)
    }

    /**
     * F-1 negative control (承重): the committed switch (换库) drops the old ledger's report slot
     * through the host's landing order, so the new graph cannot show the old ledger's amount and the
     * old in-flight result has nowhere to land.
     *
     * ABLATION (承重): remove the retirement event from [landRestoreSwitchResult] and the old payload
     * rides into the new ledger (the `null` slot assertions go RED).
     */
    @Test
    fun aCommittedRestoreSwitchDropsTheOldLedgerReportSlot() {
        val oldSlot = loadedSlot()
        val backupRestore =
            P503AppState.BackupRestore(
                overview =
                    P503AppState.OverviewEmpty(
                        state = ledger(otherLedgerId),
                        selectedTab = P503Tab.ANALYSIS,
                        reportRegion = oldSlot,
                    ),
                runningConfirm = true,
                token = RestorePreflightToken("tok", 1, "ledger-other", ByteArray(32), null),
            )
        val switched = landSwitchThroughTheHostOrder(backupRestore, BackupRestoreSwitchResult.Committed(runtimeGeneration = 4), preSwitchGeneration = 3)
        val overviewState = assertIs<P503AppState.OverviewEmpty>(switched)
        assertNull(overviewState.reportRegion)
        // A late old result has no slot to land on.
        assertSame(overviewState, reducer.reduce(overviewState, P503UiEvent.ReportPeriodResult(successRead(token = 4L), currentGeneration = 3, currentCatalogVersion = 7L)))
    }

    /**
     * R-5 (承重): the two round trips stay distinct. A COMPLETED slot survives an editor round trip
     * only through the host memory — the write-flow states carry no report field by contract — and the
     * restored slot starts NO read (零补读); the in-flight detach's bounded convergence is held by
     * [aLandingDuringAReadOnlyDetourConvergesTheCarriedSlotInsteadOfLeavingItLoading].
     *
     * ABLATION (承重): import the remembered payload as anything but the eligible current result and the
     * READY / zero-read assertions go RED; make the import write-stale and the face turns FAILED.
     */
    @Test
    fun aCompletedEditorReturnRestoresTheSlotFromTheHostMemoryWithoutARead() {
        val loaded = overview(loadedSlot(token = 4L))
        val memory = P806ReportSlotMemory()
        memory.record(loaded)
        val editor = assertIs<P503AppState.Editing>(reducer.reduce(loaded, P503UiEvent.StartNewExpense))
        val back = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(editor, P503UiEvent.Back))
        // No write happened: the rebuilt page carries no slot of its own, and the host memory still
        // holds the same graph's accepted success.
        assertNull(back.reportRegion)
        val restored =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(back, P503UiEvent.OpenReportRegion(previous = memory.previousFor(3, 7L))),
            )
        val slot = assertNotNull(restored.reportRegion)
        assertEquals(P806Period(P806PeriodKind.MONTH), slot.period)
        assertEquals(P806PeriodKind.MONTH, slot.landedKind)
        assertEquals(readInstant, slot.landedAsOf)
        assertNotNull(slot.landed)
        // Same success, no request in flight: the host's entry starts no read (零补读).
        assertTrue(!slot.loading)
        assertTrue(!slot.writeStale)
        assertEquals(P806ReportRegionFace.READY, p806ReportRegionFace(slot))
    }
}
