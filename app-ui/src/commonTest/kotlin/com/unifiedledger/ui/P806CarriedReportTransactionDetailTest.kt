package com.unifiedledger.ui

import com.unifiedledger.application.BudgetMonthResult
import com.unifiedledger.application.BudgetMonthViewResult
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.MonthlyActivity
import com.unifiedledger.application.MonthlyActivityResult
import com.unifiedledger.application.MonthlyCategoryCurrencyTotal
import com.unifiedledger.application.MonthlyCategoryTotal
import com.unifiedledger.application.MonthlyCurrencyActivity
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * P8-06 typed returnTo 批 1 (D-361): the carried-write-back test for the ONE carrier variant that
 * had no existing test — `carriedReportCarrier`'s `TransactionDetail` branch — now that the detail
 * carries a TYPED return target. The frozen design (D-360 Q1): the returnTo domain is two values,
 * `null` (HOME) and `TransactionDetailReturnTo.AccountsFlow(carriedFlow)`. Every write site is the
 * ONE paired write-back ([P503AppState.TransactionDetail.withResolvedOverview]): both carried
 * overviews — the detail's own field AND the account-flow carrier's — receive the SAME landed
 * payload instance, so the flow the close returns (the whole carried
 * [P503AppState.AccountFlow]) never holds a payload a landing forgot to update.
 *
 * The sites asserted here are exactly the D-361 roster: the report-carrier rebuild (this file's
 * namesake), the reducer's `BudgetMonthLanded` and `MonthlyActivityResult` Success transitions
 * (synthetic states — the user never sees the report landing or a monthly read complete while on
 * the detail, but D-361 requires the batch to carry them). The failure branch
 * (`InfrastructureFailure(READ, monthlyOverview = state.overview)`) is asserted UNTOUCHED: it
 * preserves the detail's own overview and drops the account-flow payload — the §七 READ residual,
 * quoted verbatim in the batch acceptance record, is exactly this interaction and is NOT fixed
 * here.
 *
 * Anonymous synthetic amounts only; no Compose runtime, no timing.
 */
class P806CarriedReportTransactionDetailTest {
    private val ledgerId = LedgerId("ledger-returnto-carrier-test")
    private val cny = CurrencyUnit("CNY", 2)
    private val expenseCategoryId = CategoryId("category-food")
    private val march = YearMonth(2026, 3)

    private val reducer = P503ReducerImpl(ParseManualExpenseAmount(), cny)

    private val emptyLedgerState = LedgerCurrentState(ledgerId, transactions = emptyList(), balances = emptyList())

    /** The HOME-entry overview: the monthly payload and domain the detail's month card rides. */
    private val monthlyPayload = monthlyActivity()

    private fun monthlyActivity(): MonthlyActivity =
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
                        categoryId = expenseCategoryId,
                        categoryName = "餐饮",
                        totals = listOf(MonthlyCategoryCurrencyTotal(cny, 3_580L, 0L)),
                        children = emptyList(),
                    ),
                ),
            incomeCategories = emptyList(),
        )

    private fun homeOverview(): P503AppState.OverviewEmpty =
        P503AppState.OverviewEmpty(
            state = emptyLedgerState,
            selectedTab = P503Tab.HOME,
            selectedMonth = march,
            selectableMonths = listOf(march, YearMonth(2026, 4)),
            monthlyActivity = monthlyPayload,
        )

    /** The account-flow surface the row was opened from: loaded rows, exact count, sampled scroll. */
    private fun carriedFlow(overview: P503AppState.OverviewEmpty): P503AppState.AccountFlow =
        P503AppState.AccountFlow(
            overview = overview,
            accountId = com.unifiedledger.domain.AccountId("asset-payment-local"),
            balanceLine = "零钱 · 资产 · CNY",
            balanceMinorUnits = -3_580L,
            balanceCurrency = cny,
            totalCount = 1,
            listScroll = ListScrollPosition(2, 10),
        )

    private fun detail(returnTo: TransactionDetailReturnTo?): P503AppState.TransactionDetail =
        P503AppState.TransactionDetail(
            overview =
                when (returnTo) {
                    is TransactionDetailReturnTo.AccountsFlow -> returnTo.carriedFlow.overview
                    null -> homeOverview()
                },
            originTab = P503Tab.HOME,
            transactionId = TransactionId("tx-1"),
            detail = com.unifiedledger.application.TransactionDetailResult.NotFound,
            returnTo = returnTo,
        )

    // ---------------------------------------------------------------- the carrier's TransactionDetail branch (D-361 site 3)

    @Test
    fun theCarrierOfANullReturnToDetailRebuildsOnlyItsOwnOverview() {
        val detailState = detail(null)
        val landedOverview = homeOverview().copy(monthlyReloadRequired = false)
        val rebuilt = assertNotNull(detailState.carriedReportCarrier()).rebuild(landedOverview)
        val rebuiltDetail = assertIs<P503AppState.TransactionDetail>(rebuilt)
        assertSame(landedOverview, rebuiltDetail.overview)
        assertNull(rebuiltDetail.returnTo)
    }

    @Test
    fun theCarrierOfAnAccountFlowDetailWritesBothOverviewsWithTheSameInstance() {
        val flow = carriedFlow(homeOverview())
        val detailState = detail(TransactionDetailReturnTo.AccountsFlow(flow))
        val landedOverview = homeOverview().copy(monthlyReloadRequired = false)
        val rebuilt = assertNotNull(detailState.carriedReportCarrier()).rebuild(landedOverview)
        val rebuiltDetail = assertIs<P503AppState.TransactionDetail>(rebuilt)
        // The paired write-back: the detail's own overview AND the carried flow's overview hold the
        // SAME landed instance — the returned flow reads either and gets the fresh payload.
        assertSame(landedOverview, rebuiltDetail.overview)
        val target = assertIs<TransactionDetailReturnTo.AccountsFlow>(rebuiltDetail.returnTo)
        assertSame(landedOverview, target.carriedFlow.overview)
        // The carried flow's own payload survived the rebuild whole.
        assertEquals(1, target.carriedFlow.totalCount)
        assertEquals(ListScrollPosition(2, 10), target.carriedFlow.listScroll)
        assertEquals("零钱 · 资产 · CNY", target.carriedFlow.balanceLine)
    }

    @Test
    fun aReportLandingOnTheAccountFlowDetailConvergesTheCarriedSlotForTheReturn() {
        // The reducer path the carrier serves: a report landing arriving while the user sits on the
        // detail converges BOTH carried overviews (the withCarriedReportOverview write), so closing
        // back to the account flow lands on a READY report instead of a stuck loading.
        val requestingOverview =
            homeOverview().copy(
                reportRegion =
                    P806ReportRegionState(
                        period = com.unifiedledger.application.P806Period(com.unifiedledger.application.P806PeriodKind.MONTH),
                        kind = com.unifiedledger.application.P806PeriodKind.MONTH,
                        requestToken = 5L,
                        loading = true,
                    ),
            )
        val flow = carriedFlow(requestingOverview)
        val detailState = detail(TransactionDetailReturnTo.AccountsFlow(flow))
        val landed =
            assertIs<P503AppState.TransactionDetail>(
                reducer.reduce(
                    detailState,
                    P503UiEvent.ReportPeriodResult(
                        P806ReportRead.Landed(
                            capture =
                                P806ReportReadCapture(
                                    request =
                                        com.unifiedledger.application.P806ReportRequestIdentity(
                                            token = 5L,
                                            ledgerId = ledgerId,
                                            period = com.unifiedledger.application.P806Period(com.unifiedledger.application.P806PeriodKind.MONTH),
                                        ),
                                    generation = 3,
                                    catalogVersion = 7L,
                                    asOf = kotlin.time.Instant.parse("2026-11-18T02:00:00Z"),
                                ),
                            result =
                                com.unifiedledger.application.P806SnapshotResult.Success(
                                    com.unifiedledger.application.P806ReportView(
                                        ledgerId = ledgerId,
                                        period = null,
                                        currencies = emptyList(),
                                        expenseCategories = emptyList(),
                                        incomeCategories = emptyList(),
                                        uncategorizedExpenseTotals = emptyList(),
                                        uncategorizedIncomeTotals = emptyList(),
                                        transactionCount = 0,
                                        futureTransactionCount = 0,
                                        detailPage = com.unifiedledger.application.P806TransactionPage(rows = emptyList(), nextCursor = null),
                                        detailCount = 0,
                                    ),
                                ),
                        ),
                        currentGeneration = 3,
                        currentCatalogVersion = 7L,
                    ),
                ),
            )
        val slot = assertNotNull(assertIs<TransactionDetailReturnTo.AccountsFlow>(landed.returnTo).carriedFlow.overview.reportRegion)
        assertTrue(!slot.loading)
        assertNotNull(slot.landed)
        // The convergence point of THIS test is loading=false and landed!=null on both carriers;
        // the face itself is EMPTY because the fixture view has period=null/count=0 (the
        // p806ReportRegionFace established reading, P503ReportRegion.kt:138).
        assertEquals(P806ReportRegionFace.EMPTY, p806ReportRegionFace(slot))
        // The detail's own overview carries the same landed slot (the HOME return path).
        val ownSlot = assertNotNull(landed.overview.reportRegion)
        assertTrue(!ownSlot.loading)
        assertNotNull(ownSlot.landed)
        assertEquals(P806ReportRegionFace.EMPTY, p806ReportRegionFace(ownSlot))
    }

    // ---------------------------------------------------------------- the monthly write site (D-361 site 2)

    @Test
    fun aMonthlySuccessOnTheAccountFlowDetailWritesBothOverviews() {
        val flow = carriedFlow(homeOverview())
        val detailState = detail(TransactionDetailReturnTo.AccountsFlow(flow))
        val payload =
            MonthlyActivity(
                ledgerId = ledgerId,
                month = march,
                currencies = emptyList(),
                expenseCategories = emptyList(),
                incomeCategories = emptyList(),
            )
        val updated =
            assertIs<P503AppState.TransactionDetail>(
                reducer.reduce(detailState, P503UiEvent.MonthlyActivityResult(MonthlyActivityResult.Success(payload), listOf(march, YearMonth(2026, 4)))),
            )
        // Both halves: the fresh payload instance rides the detail AND the carried flow.
        assertSame(payload, updated.overview.monthlyActivity)
        val target = assertIs<TransactionDetailReturnTo.AccountsFlow>(updated.returnTo)
        assertSame(payload, target.carriedFlow.overview.monthlyActivity)
        // The carried flow's own payload survived whole.
        assertEquals(1, target.carriedFlow.totalCount)
        assertEquals(ListScrollPosition(2, 10), target.carriedFlow.listScroll)
    }

    @Test
    fun aMonthlyFailureOnTheAccountFlowDetailKeepsTodayUntouchedContract() {
        // The §五.6/§七 guard, on the NEW entry: the failure branch is one line unchanged — it
        // preserves the detail's own overview as the READ-failure monthlyOverview. The account-flow
        // payload is lost with it (the registered residual; the acceptance record quotes §七).
        val flow = carriedFlow(homeOverview())
        val detailState = detail(TransactionDetailReturnTo.AccountsFlow(flow))
        val failed =
            assertIs<P503AppState.InfrastructureFailure>(
                reducer.reduce(detailState, P503UiEvent.MonthlyActivityResult(MonthlyActivityResult.Unavailable, emptyList())),
            )
        assertEquals(InfrastructureFailureContext.READ, failed.context)
        assertSame(detailState.overview, failed.monthlyOverview)
    }

    // ---------------------------------------------------------------- the budget write site (D-361 site 1)

    @Test
    fun aBudgetLandingOnTheAccountFlowDetailWritesBothOverviews() {
        val flow = carriedFlow(homeOverview())
        val detailState = detail(TransactionDetailReturnTo.AccountsFlow(flow))
        val payload =
            BudgetMonthViewResult.Success(
                com.unifiedledger.application.BudgetMonthView(
                    ledgerId,
                    march,
                    catalogVersion = 1L,
                    total = BudgetMonthResult.Success(com.unifiedledger.application.BudgetMonth(ledgerId = ledgerId, month = march, currency = cny, scope = com.unifiedledger.domain.BudgetScope.Total, limitMinorUnits = null, netExpenseMinorUnits = 3_580L, remainingMinorUnits = null, overspentMinorUnits = null)),
                    categories = emptyList(),
                ),
            )
        val applied =
            assertIs<P503AppState.TransactionDetail>(
                reducer.reduce(detailState, P503UiEvent.BudgetMonthLanded(payload)),
            )
        assertSame(payload, applied.overview.budgetView)
        val target = assertIs<TransactionDetailReturnTo.AccountsFlow>(applied.returnTo)
        assertSame(payload, target.carriedFlow.overview.budgetView)
        assertEquals(1, target.carriedFlow.totalCount)
        assertEquals(ListScrollPosition(2, 10), target.carriedFlow.listScroll)
    }

    @Test
    fun aBudgetLandingOnTheHomeDetailStillAppliesToItsOwnOverviewOnly() {
        // The existing P1-2 contract on the HOME entry (P503BudgetReducerTest's oracle), now also
        // pinning that the null-returnTo path writes no second place.
        val detailState = detail(null)
        val payload =
            BudgetMonthViewResult.Success(
                com.unifiedledger.application.BudgetMonthView(
                    ledgerId,
                    march,
                    catalogVersion = 1L,
                    total = null,
                    categories = emptyList(),
                ),
            )
        val applied = assertIs<P503AppState.TransactionDetail>(reducer.reduce(detailState, P503UiEvent.BudgetMonthLanded(payload)))
        assertSame(payload, applied.overview.budgetView)
        assertNull(applied.returnTo)
    }
}
