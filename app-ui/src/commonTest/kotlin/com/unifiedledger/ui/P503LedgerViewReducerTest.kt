package com.unifiedledger.ui

import com.unifiedledger.application.AccountCurrencyBalance
import com.unifiedledger.application.CreationEntry
import com.unifiedledger.application.CurrentVersionRow
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.MonthlyActivity
import com.unifiedledger.application.MonthlyCategoryCurrencyTotal
import com.unifiedledger.application.MonthlyCategoryTotal
import com.unifiedledger.application.MonthlyCurrencyActivity
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.TransactionDetail
import com.unifiedledger.application.TransactionDetailLeg
import com.unifiedledger.application.TransactionDetailResult
import com.unifiedledger.application.TransactionReconciliationProjection
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant
import com.unifiedledger.application.MonthlyActivityResult as MonthlyActivityQueryResult

/**
 * P7-03.C/D state-machine extension tests (spec section 6.1/6.2, table 6.2a): the five new
 * read-only events have their designed effect only in OverviewEmpty/TransactionDetail and are
 * absorbed in every other state; no new event throws anywhere; every pre-existing ISE path
 * stays locked (G-B). Monthly read failures preserve the last successful overview on
 * `InfrastructureFailure(READ)` (spec 4.3/C04) and the existing READ retry semantics are
 * unchanged (spec 6.3).
 */
class P503LedgerViewReducerTest {
    private val ledgerId = LedgerId("ledger-local-test")
    private val cny = CurrencyUnit("CNY", 2)
    private val paymentAccountId = AccountId("asset-payment-local")
    private val occurredAt = Instant.parse("2026-03-15T02:00:00Z")
    private val march = YearMonth(2026, 3)

    /** 本月 = 2026-09 for every clock read (Asia/Shanghai month of the fixed instant). */
    private val fixedClock = LedgerClock { Instant.parse("2026-09-15T02:00:00Z") }
    private val clockedReducer = P503ReducerImpl(ParseManualExpenseAmount(), cny, ledgerClock = fixedClock)
    private val bareReducer = P503ReducerImpl(ParseManualExpenseAmount(), cny)

    private val emptyState = LedgerCurrentState(ledgerId, transactions = emptyList(), balances = emptyList())
    private val oneTransactionState =
        LedgerCurrentState(
            ledgerId = ledgerId,
            transactions =
                listOf(
                    CurrentVersionRow(
                        transactionId = TransactionId("tx-1"),
                        currentVersionId = TransactionVersionId("version-1"),
                        kind = TransactionKind.EXPENSE,
                        occurredAt = occurredAt,
                        postings = listOf(Posting(PostingId("posting-1"), paymentAccountId, Money.ofMinor(-3_580L, cny))),
                    ),
                ),
            balances =
                listOf(
                    AccountCurrencyBalance(paymentAccountId, cny, ledgerSignedMinorUnits = -3_580L, displayMinorUnits = -3_580L),
                ),
        )

    private val monthlyPayload =
        MonthlyActivity(
            ledgerId = ledgerId,
            month = march,
            currencies =
                listOf(
                    MonthlyCurrencyActivity(
                        currency = cny,
                        ordinaryIncomeMinorUnits = 0L,
                        netExpenseMinorUnits = 3_580L,
                        balanceMinorUnits = -3_580L,
                        positiveExpenseMinorUnits = 3_580L,
                        refundMinorUnits = 0L,
                        transactionCount = 1,
                        countByKind = mapOf(TransactionKind.EXPENSE to 1),
                    ),
                ),
            expenseCategories =
                listOf(
                    MonthlyCategoryTotal(
                        categoryId = CategoryId("category-food"),
                        categoryName = "餐饮",
                        totals =
                            listOf(
                                MonthlyCategoryCurrencyTotal(cny, 3_580L, 0L),
                            ),
                        children = emptyList(),
                    ),
                ),
            incomeCategories = emptyList(),
        )

    private val selectableDomain = listOf(YearMonth(2026, 1), YearMonth(2026, 2), march)

    /** The domain around the clock-resolved 本月 (2026-09) used by the shift tests. */
    private val septemberDomain = listOf(YearMonth(2026, 7), YearMonth(2026, 8), YearMonth(2026, 9))

    private fun overview(
        tab: P503Tab = P503Tab.HOME,
        month: YearMonth? = null,
        domain: List<YearMonth> = emptyList(),
        payload: MonthlyActivity? = null,
        state: LedgerCurrentState = oneTransactionState,
    ): P503AppState.OverviewEmpty =
        P503AppState.OverviewEmpty(
            state = state,
            selectedTab = tab,
            selectedMonth = month,
            selectableMonths = domain,
            monthlyActivity = payload,
        )

    private fun detailResult(transactionId: String = "tx-1"): TransactionDetailResult =
        TransactionDetailResult.Success(
            TransactionDetail(
                ledgerId = ledgerId,
                transactionId = TransactionId(transactionId),
                currentVersionId = TransactionVersionId("version-$transactionId"),
                kind = TransactionKind.EXPENSE,
                occurredAt = occurredAt,
                statisticsAt = occurredAt,
                note = null,
                legs =
                    listOf(
                        TransactionDetailLeg(PostingId("posting-1"), paymentAccountId, "默认支付账户", Money.ofMinor(-3_580L, cny), categoryName = null),
                    ),
                creationEntry = CreationEntry.UNMARKED,
                reconciliation = TransactionReconciliationProjection(legs = emptyList(), rollup = null),
            ),
        )

    private fun detail(
        overview: P503AppState.OverviewEmpty,
        transactionId: String = "tx-1",
    ): P503AppState.TransactionDetail =
        P503AppState.TransactionDetail(
            overview = overview,
            originTab = overview.selectedTab,
            transactionId = TransactionId(transactionId),
            detail = detailResult(transactionId),
        )

    private fun reduceFrom(
        reducer: P503Reducer,
        state: P503AppState,
        vararg events: P503UiEvent,
    ): P503AppState = events.fold(state) { current, event -> reducer.reduce(current, event) }

    // ---- SelectTransaction (matrix: effect on OverviewEmpty, absorbed everywhere else) ----

    @Test
    fun selectTransactionEntersTheDetailWithThePreservedOverview() {
        val source = overview(month = march, domain = selectableDomain, payload = monthlyPayload)
        val entered =
            assertIs<P503AppState.TransactionDetail>(
                clockedReducer.reduce(source, P503UiEvent.SelectTransaction(TransactionId("tx-1"), detailResult())),
            )
        assertSame(source, entered.overview)
        assertEquals(P503Tab.HOME, entered.originTab)
        assertEquals(TransactionId("tx-1"), entered.transactionId)
        val success = assertIs<TransactionDetailResult.Success>(entered.detail)
        assertEquals(TransactionId("tx-1"), success.detail.transactionId)
    }

    @Test
    fun selectTransactionIsAbsorbedInEveryOtherState() {
        everyNonOverviewState { state, reducer ->
            assertSame(state, reducer.reduce(state, P503UiEvent.SelectTransaction(TransactionId("tx-9"), detailResult("tx-9"))))
        }
    }

    // ---- CloseTransactionDetail (matrix: effect on TransactionDetail, absorbed everywhere else) ----

    @Test
    fun closeTransactionDetailReturnsToTheOriginalOverviewPreservingTabAndMonth() {
        val source = overview(tab = P503Tab.ANALYSIS, month = march)
        val entered = assertIs<P503AppState.TransactionDetail>(clockedReducer.reduce(source, P503UiEvent.SelectTransaction(TransactionId("tx-1"), detailResult())))
        val closed = clockedReducer.reduce(entered, P503UiEvent.CloseTransactionDetail)
        assertSame(source, closed)
        val returned = assertIs<P503AppState.OverviewEmpty>(closed)
        assertEquals(P503Tab.ANALYSIS, returned.selectedTab)
        assertEquals(march, returned.selectedMonth)
    }

    @Test
    fun backOnTheDetailClosesToTheOverviewWithTheSameSemantics() {
        val source = overview(month = march)
        val entered = assertIs<P503AppState.TransactionDetail>(clockedReducer.reduce(source, P503UiEvent.SelectTransaction(TransactionId("tx-1"), detailResult())))
        val closed = clockedReducer.reduce(entered, P503UiEvent.Back)
        assertSame(source, closed)
    }

    @Test
    fun closeTransactionDetailIsAbsorbedInEveryOtherState() {
        // TransactionDetail is excluded: there CloseTransactionDetail is the designed effect.
        everyNonOverviewState { state, reducer ->
            assertSame(state, reducer.reduce(state, P503UiEvent.CloseTransactionDetail))
        }
        // The overview absorbs it too (there is no detail to close).
        val source = overview()
        assertSame(source, clockedReducer.reduce(source, P503UiEvent.CloseTransactionDetail))
    }

    // ---- SelectMonth (matrix: effect on OverviewEmpty within the frozen domain, absorbed otherwise) ----

    @Test
    fun selectMonthUpdatesTheSelectionWithinTheFrozenDomain() {
        val source = overview(domain = selectableDomain)
        val selected =
            assertIs<P503AppState.OverviewEmpty>(
                clockedReducer.reduce(source, P503UiEvent.SelectMonth(YearMonth(2026, 2))),
            )
        assertEquals(YearMonth(2026, 2), selected.selectedMonth)
        assertSame(source.state, selected.state)
    }

    @Test
    fun outOfDomainSelectMonthIsAbsorbedWithZeroStateChange() {
        val source = overview(domain = selectableDomain)
        assertSame(source, clockedReducer.reduce(source, P503UiEvent.SelectMonth(YearMonth(2026, 6))))
    }

    @Test
    fun selectMonthOnAnEmptyDomainIsAbsorbed() {
        // Residual boundary (b): an empty ledger has no selectable month.
        val emptyLedgerOverview = overview(domain = emptyList(), state = emptyState)
        assertSame(emptyLedgerOverview, clockedReducer.reduce(emptyLedgerOverview, P503UiEvent.SelectMonth(march)))
    }

    @Test
    fun selectMonthIsAbsorbedInEveryOtherState() {
        everyNonOverviewState { state, reducer ->
            assertSame(state, reducer.reduce(state, P503UiEvent.SelectMonth(march)))
        }
        val detailState = detail(overview())
        assertSame(detailState, clockedReducer.reduce(detailState, P503UiEvent.SelectMonth(march)))
    }

    // ---- AnalysisMonthShift (matrix: effect on OverviewEmpty, absorbed everywhere else) ----

    @Test
    fun analysisMonthShiftMovesTheCursorWithinTheFrozenSelectMonthDomain() {
        val shifted =
            assertIs<P503AppState.OverviewEmpty>(
                clockedReducer.reduce(overview(domain = septemberDomain), P503UiEvent.AnalysisMonthShift(-1)),
            )
        // 本月 resolved from the injected clock (2026-09) shifted back one month.
        assertEquals(YearMonth(2026, 8), shifted.selectedMonth)
    }

    @Test
    fun analysisMonthShiftMovesTheCursorFromTheSelectedMonthAcrossYearBounds() {
        val januaryDomain = listOf(YearMonth(2025, 11), YearMonth(2025, 12), YearMonth(2026, 1))
        val shifted =
            assertIs<P503AppState.OverviewEmpty>(
                clockedReducer.reduce(overview(month = YearMonth(2026, 1), domain = januaryDomain), P503UiEvent.AnalysisMonthShift(-2)),
            )
        assertEquals(YearMonth(2025, 11), shifted.selectedMonth)
    }

    @Test
    fun analysisMonthShiftOutsideTheSelectMonthDomainIsAbsorbed() {
        // F9 (P703SPEC-10): the analysis region moves the shared cursor under the same admission
        // rule as SelectMonth, so it can never request a month the selector will never offer.
        val source = overview(month = march, domain = selectableDomain)
        assertSame(source, clockedReducer.reduce(source, P503UiEvent.AnalysisMonthShift(1)))
        assertSame(source, clockedReducer.reduce(source, P503UiEvent.AnalysisMonthShift(-3)))
        // An in-domain shift still moves: 2026-01 + 1 == 2026-02.
        val moved =
            assertIs<P503AppState.OverviewEmpty>(
                clockedReducer.reduce(overview(month = YearMonth(2026, 1), domain = selectableDomain), P503UiEvent.AnalysisMonthShift(1)),
            )
        assertEquals(YearMonth(2026, 2), moved.selectedMonth)
    }

    @Test
    fun analysisMonthShiftIsAbsorbedWhenNoSelectableMonthExists() {
        // An empty ledger (residual boundary (b)) offers no month, so the shared cursor cannot
        // move onto a month the monthly read would have to invent.
        val source = overview(domain = emptyList())
        assertSame(source, clockedReducer.reduce(source, P503UiEvent.AnalysisMonthShift(-1)))
    }

    @Test
    fun analysisMonthShiftReRequestsOnlyWhenTheCursorActuallyMoved() {
        // G3: trigger (c) still re-requests for a shift that moves the cursor, but an absorbed
        // shift must not fire a wasted monthly read.
        val before = overview(month = YearMonth(2026, 1), domain = selectableDomain)
        val moved = clockedReducer.reduce(before, P503UiEvent.AnalysisMonthShift(1))
        val target = assertIs<P503AppState.OverviewEmpty>(analysisMonthShiftReRequest(before, moved))
        assertEquals(YearMonth(2026, 2), target.selectedMonth)

        val atTheEdge = overview(month = march, domain = selectableDomain)
        val absorbed = clockedReducer.reduce(atTheEdge, P503UiEvent.AnalysisMonthShift(1))
        assertSame(atTheEdge, absorbed)
        assertNull(analysisMonthShiftReRequest(atTheEdge, absorbed))

        val withoutADomain = overview(month = march, domain = emptyList())
        assertNull(analysisMonthShiftReRequest(withoutADomain, clockedReducer.reduce(withoutADomain, P503UiEvent.AnalysisMonthShift(-1))))

        // No overview on either side: nothing to re-request.
        assertNull(analysisMonthShiftReRequest(null, atTheEdge))
        assertNull(analysisMonthShiftReRequest(atTheEdge, P503AppState.Ready))
    }

    @Test
    fun analysisMonthShiftWithoutAUsableClockOrSelectionIsAbsorbed() {
        // No clock and no selected month: the base month cannot be resolved, so the shift is absorbed.
        val source = overview()
        assertSame(source, bareReducer.reduce(source, P503UiEvent.AnalysisMonthShift(-1)))
    }

    @Test
    fun analysisMonthShiftIsAbsorbedInEveryOtherState() {
        everyNonOverviewState { state, reducer ->
            assertSame(state, reducer.reduce(state, P503UiEvent.AnalysisMonthShift(-1)))
        }
        val detailState = detail(overview())
        assertSame(detailState, clockedReducer.reduce(detailState, P503UiEvent.AnalysisMonthShift(-1)))
    }

    // ---- MonthlyActivityResult (matrix: payload effect on OverviewEmpty/TransactionDetail, absorbed elsewhere) ----

    @Test
    fun monthlyActivitySuccessUpdatesThePayloadAndTheSelectableDomain() {
        val source = overview(month = march, domain = emptyList())
        val updated =
            assertIs<P503AppState.OverviewEmpty>(
                clockedReducer.reduce(source, P503UiEvent.MonthlyActivityResult(MonthlyActivityQueryResult.Success(monthlyPayload), selectableDomain)),
            )
        assertSame(monthlyPayload, updated.monthlyActivity)
        assertEquals(selectableDomain, updated.selectableMonths)
        assertEquals(march, updated.selectedMonth)
    }

    @Test
    fun monthlyActivityFailurePreservesTheLastSuccessfulOverviewOnTheReadFailure() {
        val source = overview(month = march, domain = selectableDomain, payload = monthlyPayload)
        val failed =
            assertIs<P503AppState.InfrastructureFailure>(
                clockedReducer.reduce(source, P503UiEvent.MonthlyActivityResult(MonthlyActivityQueryResult.Unavailable, emptyList())),
            )
        assertEquals(InfrastructureFailureContext.READ, failed.context)
        assertNull(failed.draft)
        assertNull(failed.requestId)
        // C04 (F8): the last successful overview is preserved unconditionally — the requirement
        // is asserted, never guarded away by a null check.
        val preserved = assertIs<P503AppState.OverviewEmpty>(retainedReadFailureOverview(failed))
        assertSame(source, preserved)
        assertSame(monthlyPayload, preserved.monthlyActivity)
        assertEquals(march, preserved.selectedMonth)
        assertEquals(selectableDomain, preserved.selectableMonths)
        assertEquals(P503Tab.HOME, preserved.selectedTab)
    }

    @Test
    fun monthlyActivityInvalidStateFailsClosedTheSameWay() {
        val source = overview()
        val failed =
            assertIs<P503AppState.InfrastructureFailure>(
                clockedReducer.reduce(source, P503UiEvent.MonthlyActivityResult(MonthlyActivityQueryResult.InvalidState, emptyList())),
            )
        assertEquals(InfrastructureFailureContext.READ, failed.context)
        assertSame(source, failed.monthlyOverview)
    }

    @Test
    fun readFailuresWithoutARetainedMonthlyOverviewKeepTheBareFailurePresentation() {
        // F1/F8: every pre-P7-03 READ failure path has no retained monthly overview, so the bare
        // recoverable page applies; only a monthly-cycle failure selects the retained surface.
        val failedInitialLoad =
            assertIs<P503AppState.InfrastructureFailure>(clockedReducer.reduce(P503AppState.Ready, P503UiEvent.InitialLoadFailed))
        assertEquals(InfrastructureFailureContext.READ, failedInitialLoad.context)
        assertNull(retainedReadFailureOverview(failedInitialLoad))

        val failedRefresh =
            assertIs<P503AppState.InfrastructureFailure>(clockedReducer.reduce(overview(), P503UiEvent.RefreshFailed))
        assertEquals(InfrastructureFailureContext.READ, failedRefresh.context)
        assertNull(retainedReadFailureOverview(failedRefresh))

        val monthlyFailure =
            assertIs<P503AppState.InfrastructureFailure>(
                clockedReducer.reduce(
                    overview(month = march, domain = selectableDomain, payload = monthlyPayload),
                    P503UiEvent.MonthlyActivityResult(MonthlyActivityQueryResult.Unavailable, emptyList()),
                ),
            )
        assertSame(monthlyFailure.monthlyOverview, retainedReadFailureOverview(monthlyFailure))
    }

    @Test
    fun readRetryAfterAMonthlyFailurePreservesTheMonthCursorAndTheSelectableDomain() {
        // F2 (spec 6.2 residual boundary (a)): RetryRefresh does not re-request the monthly
        // payload, but it must not strand the user on a month-less, stepper-less overview either.
        val source = overview(month = march, domain = selectableDomain, payload = monthlyPayload)
        val failed =
            assertIs<P503AppState.InfrastructureFailure>(
                clockedReducer.reduce(source, P503UiEvent.MonthlyActivityResult(MonthlyActivityQueryResult.Unavailable, emptyList())),
            )
        val recovered =
            assertIs<P503AppState.OverviewEmpty>(
                clockedReducer.reduce(failed, P503UiEvent.RefreshResult(oneTransactionState)),
            )
        assertEquals(P503Tab.HOME, recovered.selectedTab)
        assertEquals(march, recovered.selectedMonth)
        assertEquals(selectableDomain, recovered.selectableMonths)
        // The payload was not re-requested, so the region says so explicitly instead of showing
        // the stale payload as current (and never as 该月无交易).
        assertNull(recovered.monthlyActivity)
        assertEquals(
            MonthlyRegionState.NOT_LOADED,
            monthlyRegionState(recovered.monthlyActivity, recovered.monthlyReloadRequired),
        )
    }

    @Test
    fun readRetryWithoutAUsableMonthlyDomainStillSurvivesTheMonthCursor() {
        // F2: even when the failure happened before any domain was known, a selected month keeps
        // the re-select affordance usable (SelectMonth re-requests unconditionally via trigger (b)).
        val source = overview(month = march, domain = emptyList(), payload = null)
        val failed =
            assertIs<P503AppState.InfrastructureFailure>(
                clockedReducer.reduce(source, P503UiEvent.MonthlyActivityResult(MonthlyActivityQueryResult.Unavailable, emptyList())),
            )
        val recovered =
            assertIs<P503AppState.OverviewEmpty>(
                clockedReducer.reduce(failed, P503UiEvent.RefreshResult(oneTransactionState)),
            )
        assertEquals(march, recovered.selectedMonth)
        assertEquals(emptyList(), recovered.selectableMonths)
        assertTrue(recovered.monthlyReloadRequired)
    }

    @Test
    fun preMonthlyReadRetryKeepsThePreviousOverviewSemantics() {
        // F2 regression guard: with no retained monthly overview the refresh arm is unchanged.
        val recovered =
            assertIs<P503AppState.OverviewEmpty>(
                clockedReducer.reduce(
                    P503AppState.InfrastructureFailure(InfrastructureFailureContext.READ),
                    P503UiEvent.RefreshResult(oneTransactionState),
                ),
            )
        assertEquals(P503Tab.HOME, recovered.selectedTab)
        assertNull(recovered.selectedMonth)
        assertEquals(emptyList(), recovered.selectableMonths)
        assertNull(recovered.monthlyActivity)
        assertFalse(recovered.monthlyReloadRequired)
    }

    @Test
    fun monthlyCycleSuccessClearsTheReloadFlag() {
        val source = overview(month = march, domain = emptyList()).copy(monthlyReloadRequired = true)
        val updated =
            assertIs<P503AppState.OverviewEmpty>(
                clockedReducer.reduce(source, P503UiEvent.MonthlyActivityResult(MonthlyActivityQueryResult.Success(monthlyPayload), selectableDomain)),
            )
        assertSame(monthlyPayload, updated.monthlyActivity)
        assertFalse(updated.monthlyReloadRequired)
        assertEquals(MonthlyRegionState.LOADED, monthlyRegionState(updated.monthlyActivity, updated.monthlyReloadRequired))
    }

    @Test
    fun everyCycleFailureVariantTakesTheSameTypedReadFailurePath() {
        // F3: the month payload, the SelectMonth domain and the trend share one typed cycle, so a
        // sub-read shortfall arrives here as Unavailable/InvalidState — never as a disabled
        // selector or a bare 暂无趋势数据 — and preserves the same retained overview.
        val source = overview(month = march, domain = selectableDomain, payload = monthlyPayload)
        listOf(MonthlyActivityQueryResult.Unavailable, MonthlyActivityQueryResult.InvalidState).forEach { failure ->
            val failed =
                assertIs<P503AppState.InfrastructureFailure>(
                    clockedReducer.reduce(source, P503UiEvent.MonthlyActivityResult(failure, emptyList())),
                )
            assertEquals(InfrastructureFailureContext.READ, failed.context)
            assertSame(source, failed.monthlyOverview)
            assertSame(source, retainedReadFailureOverview(failed))
        }
    }

    @Test
    fun monthlyActivitySuccessInsideTheDetailUpdatesTheStoredOverview() {
        val source = overview(month = march, payload = null)
        val entered = assertIs<P503AppState.TransactionDetail>(clockedReducer.reduce(source, P503UiEvent.SelectTransaction(TransactionId("tx-1"), detailResult())))
        val updated =
            assertIs<P503AppState.TransactionDetail>(
                clockedReducer.reduce(entered, P503UiEvent.MonthlyActivityResult(MonthlyActivityQueryResult.Success(monthlyPayload), selectableDomain)),
            )
        // The stored back-target overview carries the fresh payload and domain (详情态同语义).
        assertEquals(march, updated.overview.selectedMonth)
        assertSame(monthlyPayload, updated.overview.monthlyActivity)
        assertEquals(selectableDomain, updated.overview.selectableMonths)
        assertEquals(P503Tab.HOME, updated.overview.selectedTab)
    }

    @Test
    fun monthlyActivityFailureInsideTheDetailPreservesTheStoredOverview() {
        val source = overview(month = march, payload = monthlyPayload)
        val entered = assertIs<P503AppState.TransactionDetail>(clockedReducer.reduce(source, P503UiEvent.SelectTransaction(TransactionId("tx-1"), detailResult())))
        val failed =
            assertIs<P503AppState.InfrastructureFailure>(
                clockedReducer.reduce(entered, P503UiEvent.MonthlyActivityResult(MonthlyActivityQueryResult.Unavailable, emptyList())),
            )
        assertEquals(InfrastructureFailureContext.READ, failed.context)
        assertSame(source, failed.monthlyOverview)
    }

    @Test
    fun monthlyActivityResultIsAbsorbedInEveryOtherState() {
        everyNonOverviewState { state, reducer ->
            assertSame(state, reducer.reduce(state, P503UiEvent.MonthlyActivityResult(MonthlyActivityQueryResult.Success(monthlyPayload), selectableDomain)))
            assertSame(state, reducer.reduce(state, P503UiEvent.MonthlyActivityResult(MonthlyActivityQueryResult.Unavailable, emptyList())))
        }
    }

    // ---- preserved overview fields across existing transitions ----

    @Test
    fun tabSwitchesAndOrdinaryRefreshesPreserveTheMonthPayloadAndDomain() {
        val source = overview(month = march, domain = selectableDomain, payload = monthlyPayload)
        val accounts =
            assertIs<P503AppState.OverviewEmpty>(
                clockedReducer.reduce(source, P503UiEvent.SelectTab(P503Tab.ACCOUNTS)),
            )
        assertEquals(march, accounts.selectedMonth)
        assertSame(monthlyPayload, accounts.monthlyActivity)
        assertEquals(selectableDomain, accounts.selectableMonths)

        val refreshed =
            assertIs<P503AppState.OverviewEmpty>(
                clockedReducer.reduce(source, P503UiEvent.RefreshResult(emptyState)),
            )
        assertEquals(march, refreshed.selectedMonth)
        assertSame(monthlyPayload, refreshed.monthlyActivity)
        assertEquals(selectableDomain, refreshed.selectableMonths)
    }

    // ---- locked ISE behavior (G-B: unlisted combinations stay programming errors) ----

    @Test
    fun existingAndNewUnlistedCombinationsRemainIllegalState() {
        // Pre-existing locked path: RetryRefresh on the overview is still unlisted.
        assertFailsWith<IllegalStateException> { clockedReducer.reduce(overview(), P503UiEvent.RetryRefresh) }
        // The new state lists only its designed events; every other existing event stays ISE.
        val detailState = detail(overview())
        assertFailsWith<IllegalStateException> { clockedReducer.reduce(detailState, P503UiEvent.RetryRefresh) }
        assertFailsWith<IllegalStateException> { clockedReducer.reduce(detailState, P503UiEvent.StartNewExpense) }
        assertFailsWith<IllegalStateException> { clockedReducer.reduce(detailState, P503UiEvent.Confirm) }
        assertFailsWith<IllegalStateException> { clockedReducer.reduce(detailState, P503UiEvent.SelectTab(P503Tab.HOME)) }
    }

    // ---- helpers ----

    /** Runs the block against every state other than OverviewEmpty/TransactionDetail (matrix column 3). */
    private fun everyNonOverviewState(block: (P503AppState, P503Reducer) -> Unit) {
        val draft = ManualExpenseDraft(paymentAccountId, null, "35.80", occurredAt)
        val requestId = RequestId("request-1")
        val states =
            listOf<P503AppState>(
                P503AppState.Ready,
                P503AppState.Editing(draft, requestId),
                P503AppState.AwaitingConfirmation(draft, requestId),
                P503AppState.Submitting(draft, requestId),
                P503AppState.Created,
                P503AppState.NoChange,
                P503AppState.Recovered,
                P503AppState.RequestIdentityConflict(draft, requestId),
                P503AppState.DomainRejected(draft, requestId),
                P503AppState.InfrastructureFailure(InfrastructureFailureContext.SUBMISSION, draft, requestId),
                P503AppState.InfrastructureFailure(InfrastructureFailureContext.READ),
                P503AppState.UnknownCommit(draft, requestId),
            )
        states.forEach { state -> block(state, clockedReducer) }
    }
}
