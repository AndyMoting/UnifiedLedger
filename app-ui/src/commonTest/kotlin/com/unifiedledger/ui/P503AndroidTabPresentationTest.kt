package com.unifiedledger.ui

import com.unifiedledger.application.BudgetMonthView
import com.unifiedledger.application.BudgetMonthViewResult
import com.unifiedledger.application.CatalogAuthority
import com.unifiedledger.application.CatalogAuthorityReader
import com.unifiedledger.application.CatalogConsumerSession
import com.unifiedledger.application.CatalogSnapshotView
import com.unifiedledger.application.CategoryShareFailure
import com.unifiedledger.application.CategoryTreeView
import com.unifiedledger.application.ConfirmedExpenseReceipt
import com.unifiedledger.application.ConfirmedIncomeReceipt
import com.unifiedledger.application.ConfirmedTransferReceipt
import com.unifiedledger.application.CurrentVersionRow
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.LedgerCurrentStateReadPort
import com.unifiedledger.application.LedgerEntryRow
import com.unifiedledger.application.ManualExpenseCommitRecord
import com.unifiedledger.application.ManualIncomeCommitRecord
import com.unifiedledger.application.ManualTransferCommitRecord
import com.unifiedledger.application.MonthlyActivity
import com.unifiedledger.application.MonthlyCategoryCurrencyTotal
import com.unifiedledger.application.MonthlyCategoryTotal
import com.unifiedledger.application.MonthlyCurrencyActivity
import com.unifiedledger.application.P806CalendarCurrencyView
import com.unifiedledger.application.P806CalendarDayView
import com.unifiedledger.application.P806Window
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.presentationCategoryTintSlot
import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * D-369 B (spec section 7.1): the three main tabs' Android presentation decisions.
 *
 * Every assertion below is a pure fold — no Compose runtime, no device. The vectors cover the
 * approved B behaviour: home titles/badges/tints and per-leg amounts, the accounts management
 * secondary route, the analysis subtraction, the horizontal category ranking, the empty calendar
 * day, and the guarantee that the LEGACY profile keeps the pre-B output.
 */
class P503AndroidTabPresentationTest {
    private val cny = CurrencyUnit("CNY", 2)
    private val jpy = CurrencyUnit("JPY", 0)
    private val ledgerId = LedgerId("ledger-b-presentation")

    // ---------------------------------------------------------------------------------------------
    // Home: titles, badges and tints
    // ---------------------------------------------------------------------------------------------

    @Test
    fun theHomeTitlePrefersTheNoteThenTheSingleCategoryThenTheBusinessType() {
        val noteRow = row(TransactionKind.EXPENSE, "午饭", listOf(posting("p1", "acct-expense", -1_234L)))
        assertEquals("午饭", homeRowAndroidPresentation(noteRow, categorySnapshot(), catalog).title)

        val singleCategory = row(TransactionKind.EXPENSE, null, listOf(posting("p1", "acct-expense", -1_234L)))
        assertEquals("早餐", homeRowAndroidPresentation(singleCategory, categorySnapshot(), catalog).title)

        val noCategory = row(TransactionKind.EXPENSE, null, listOf(posting("p1", "acct-asset", -1_234L)))
        assertEquals("支出", homeRowAndroidPresentation(noCategory, categorySnapshot(), catalog).title)
    }

    @Test
    fun homeBadgesFollowThePostingOrderAreCappedAtThreeAndStateTheRest() {
        val snapshot =
            snapshot(
                listOf(
                    category("cat-a", null, "餐饮", "acct-c1"),
                    category("cat-b", null, "交通", "acct-c2"),
                    category("cat-c", null, "房租", "acct-c3"),
                    category("cat-d", null, "娱乐", "acct-c4"),
                ),
            )
        val four =
            row(
                TransactionKind.EXPENSE,
                "混合",
                listOf(
                    posting("p1", "acct-c4", -1L),
                    posting("p2", "acct-c3", -2L),
                    posting("p3", "acct-c2", -3L),
                    posting("p4", "acct-c1", -4L),
                ),
            )
        val presentation = homeRowAndroidPresentation(four, snapshot, catalog)

        assertEquals(listOf("娱乐", "房租", "交通"), presentation.categoryBadges.map { it.name })
        assertEquals("其余 1 个", presentation.remainingBadgesText)
        assertEquals(listOf("#娱乐", "#房租", "#交通"), presentation.categoryBadges.map(::homeBadgeText))

        val two =
            homeRowAndroidPresentation(
                row(TransactionKind.EXPENSE, "x", listOf(posting("p1", "acct-c1", -1L), posting("p2", "acct-c2", -2L))),
                snapshot,
                catalog,
            )
        assertNull(two.remainingBadgesText)
    }

    @Test
    fun aSameNameCategoryKeepsItsParentPathInTheBadgeTextSoColourIsNeverTheOnlySignal() {
        val snapshot =
            snapshot(
                listOf(
                    category("cat-food", null, "餐饮", null),
                    category("cat-breakfast", "cat-food", "早餐", "acct-food"),
                    category("cat-travel", null, "交通", null),
                    category("cat-commute", "cat-travel", "早餐", "acct-commute"),
                ),
            )
        val both = row(TransactionKind.EXPENSE, "x", listOf(posting("p1", "acct-food", -1L), posting("p2", "acct-commute", -2L)))
        val presentation = homeRowAndroidPresentation(both, snapshot, catalog)

        assertEquals(
            listOf("#餐饮 > 早餐", "#交通 > 早餐"),
            presentation.categoryBadges.map(::homeBadgeText),
        )
    }

    @Test
    fun theCategoryTintIsAStableSlotOverTheExistingPalette() {
        // Same id -> same slot regardless of call site or list order; the palette index is in range.
        val slot = homeBadgeTintSlot(CategoryId("cat-food"), 4)
        assertEquals(slot, homeBadgeTintSlot(CategoryId("cat-food"), 4))
        assertTrue(slot in 0 until 4)
        assertEquals(slot, presentationCategoryTintSlot(CategoryId("cat-food"), 4))
    }

    // ---------------------------------------------------------------------------------------------
    // Home: per-leg amounts
    // ---------------------------------------------------------------------------------------------

    @Test
    fun everyRealAssetAndLiabilityLegStaysItsOwnExactLine() {
        val transfer =
            row(
                TransactionKind.ACCOUNT_TRANSFER,
                "转出",
                listOf(posting("p1", "acct-asset", -1_234L), posting("p2", "acct-asset-2", 1_234L)),
            )
        val legs = homeRowAndroidPresentation(transfer, categorySnapshot(), catalog).amountLines

        assertEquals(listOf("acct-asset", "acct-asset-2"), legs.map { it.accountId.value })
        assertEquals(listOf("-12.34 CNY", "12.34 CNY"), legs.map { it.amount.text })
    }

    @Test
    fun aMixedPaymentAcrossCurrenciesIsNeverCombinedOrConverted() {
        val mixed =
            row(
                TransactionKind.EXPENSE,
                "混合支付",
                listOf(
                    posting("p1", "acct-asset", -1_234L),
                    posting("p2", "acct-jpy", -500L, currency = jpy),
                    posting("p3", "acct-expense", 1_734L),
                ),
            )
        val presentation = homeRowAndroidPresentation(mixed, categorySnapshot(), catalog)

        // The hidden EXPENSE counterparty leg is not an amount line; the two real legs keep their
        // own currency and are never summed.
        assertEquals(listOf("-12.34 CNY", "-500 JPY"), presentation.amountLines.map { it.amount.text })
        assertNull(presentation.amountAbsenceText)
    }

    @Test
    fun aSingleRealLegIsStillExactlyOneLine() {
        val expense =
            row(TransactionKind.EXPENSE, "午饭", listOf(posting("p1", "acct-asset", -1_234L), posting("p2", "acct-expense", 1_234L)))
        val legs = homeRowAndroidPresentation(expense, categorySnapshot(), catalog).amountLines
        assertEquals(1, legs.size)
        assertEquals("-12.34 CNY", legs.single().amount.text)
    }

    @Test
    fun aMissingCatalogStatesTheAbsenceInsteadOfAnEmptyAmountList() {
        val presentation =
            homeRowAndroidPresentation(row(TransactionKind.EXPENSE, "x", listOf(posting("p1", "acct-asset", -1L))), null, null)
        assertTrue(presentation.amountLines.isEmpty())
        assertEquals("未加载", presentation.amountAbsenceText)
    }

    @Test
    fun theHomeTalkbackSentenceCarriesTitleBadgesAndEveryExactAmount() {
        // A real expense has both legs: the real ASSET leg carries the amount, and the hidden
        // EXPENSE counterparty leg (acct-expense) carries the catalog association. The fixture must
        // not map the real asset account as a category.
        val presentation =
            homeRowAndroidPresentation(
                row(
                    TransactionKind.EXPENSE,
                    "午饭",
                    listOf(posting("p1", "acct-asset", -1_234L), posting("p2", "acct-expense", 1_234L)),
                ),
                categorySnapshot(),
                catalog,
            )
        assertEquals("午饭", presentation.title)
        assertEquals(listOf("早餐"), presentation.categoryBadges.map { it.name })
        val description = homeRowAndroidContentDescription(presentation)

        assertTrue(description.contains("午饭"))
        assertTrue(description.contains("#早餐"))
        assertTrue(description.contains("-12.34 CNY"))
        assertTrue(description.contains("统计时间 2026-11-10T20:00"))
        assertTrue(description.contains("发生时间 2026-11-09T20:00"))
        // The real ASSET leg is an amount line, never a category badge.
        assertFalse(presentation.categoryBadges.any { it.name.contains("acct-asset") || it.name.contains("现金") })
        assertEquals(listOf("acct-asset"), presentation.amountLines.map { it.accountId.value })
    }

    // ---------------------------------------------------------------------------------------------
    // Home: the removed tail and footer
    // ---------------------------------------------------------------------------------------------

    @Test
    fun theDuplicateBalancesTailIsGoneOnlyOnTheAndroidProfile() {
        assertTrue(homeShowsAccountBalancesTail(UiPresentationProfile.LEGACY))
        assertFalse(homeShowsAccountBalancesTail(UiPresentationProfile.ANDROID))
    }

    @Test
    fun theMeaninglessEndOfListLineIsGoneWhileLoadingFailureAndRetryStayExplicit() {
        val end = FlowRowsView(rows = emptyList(), total = 3, nextCursor = null)
        assertNull(flowFooterForProfile(end, UiPresentationProfile.ANDROID))
        assertEquals(FlowFooterState.END, flowFooterForProfile(end, UiPresentationProfile.LEGACY))

        assertEquals(FlowFooterState.LOADING, flowFooterForProfile(end.copy(loadingMore = true), UiPresentationProfile.ANDROID))
        assertEquals(FlowFooterState.RETRY, flowFooterForProfile(end.copy(loadFailed = true), UiPresentationProfile.ANDROID))
    }

    // ---------------------------------------------------------------------------------------------
    // Accounts: the management secondary route
    // ---------------------------------------------------------------------------------------------

    @Test
    fun theManagementSurfaceIsASecondaryRouteOnlyOnAndroid() {
        assertFalse(accountsCatalogManagementRouteActive(UiPresentationProfile.LEGACY, catalogManagementSurface = true))
        assertFalse(accountsCatalogManagementRouteActive(UiPresentationProfile.ANDROID, catalogManagementSurface = false))
        assertTrue(accountsCatalogManagementRouteActive(UiPresentationProfile.ANDROID, catalogManagementSurface = true))
    }

    @Test
    fun theCatalogManagementTitleKeepsTheOldCopyOnLegacyAndOnlyTheAndroidFirstScreenSaysAccounts() {
        // Only the Android first screen is 「账户」.
        assertEquals("账户", catalogManagementScreenTitle(UiPresentationProfile.ANDROID, managementRouteActive = false))
        // The management route keeps the pre-B title.
        assertEquals("账户与分类管理", catalogManagementScreenTitle(UiPresentationProfile.ANDROID, managementRouteActive = true))
        // LEGACY never has the route and must keep the old copy.
        assertEquals("账户与分类管理", catalogManagementScreenTitle(UiPresentationProfile.LEGACY, managementRouteActive = false))
        assertEquals("账户与分类管理", catalogManagementScreenTitle(UiPresentationProfile.LEGACY, managementRouteActive = true))
    }

    @Test
    fun theAccountsFirstScreenKeepsTheAssetSummaryAndMovesTheManagementSectionsToTheRoute() {
        // Android first screen: no management sections (they are the route's body).
        assertFalse(accountsShowsManagementSections(UiPresentationProfile.ANDROID, managementRouteActive = false))
        assertTrue(accountsShowsManagementSections(UiPresentationProfile.ANDROID, managementRouteActive = true))
        // LEGACY renders the pre-B single surface exactly as before.
        assertTrue(accountsShowsManagementSections(UiPresentationProfile.LEGACY, managementRouteActive = false))
    }

    // ---------------------------------------------------------------------------------------------
    // Analysis: the subtraction
    // ---------------------------------------------------------------------------------------------

    @Test
    fun theRawEnumerationAndCurrencyStackLeavesTheAndroidAnalysisScreen() {
        val activity = monthlyActivity(income = 0L, expense = 1_234L)
        assertTrue(androidAnalysisLayout(UiPresentationProfile.LEGACY, activity).showsRawSummary)
        assertFalse(androidAnalysisLayout(UiPresentationProfile.ANDROID, activity).showsRawSummary)
    }

    @Test
    fun theIncomeCategoryRegionIsAbsentWhenTheMonthHasNoIncome() {
        val noIncome = monthlyActivity(income = 0L, expense = 1_234L)
        assertFalse(androidAnalysisLayout(UiPresentationProfile.ANDROID, noIncome).showsIncomeRegion)
        // The monthly summary still renders, so the screen is never blank.
        assertTrue(androidAnalysisLayout(UiPresentationProfile.ANDROID, noIncome).showsExpenseRanking)

        val withIncome = monthlyActivity(income = 5_000L, expense = 1_234L)
        assertTrue(androidAnalysisLayout(UiPresentationProfile.ANDROID, withIncome).showsIncomeRegion)
    }

    @Test
    fun theIncomeRegionStatesNotLoadedInsteadOfNoIncomeWhileTheMonthIsAbsent() {
        // A not-loaded month (no payload) is not the loaded "no income" claim: the region stays and
        // says so. Only a loaded Success with no income hides it.
        assertTrue(androidAnalysisLayout(UiPresentationProfile.ANDROID, null).showsIncomeRegion)
        assertFalse(androidAnalysisLayout(UiPresentationProfile.ANDROID, monthlyActivity(income = 0L, expense = 1_234L)).showsIncomeRegion)
        assertTrue(
            androidAnalysisLayout(
                UiPresentationProfile.ANDROID,
                monthlyActivity(income = 0L, expense = 1_234L),
                monthlyReloadRequired = true,
            ).showsIncomeRegion,
        )
        // No activity can ever answer "no income" — the helper's claim is loaded-month only.
        assertFalse(activityHasIncome(null))
    }

    @Test
    fun theTrendAndTheIndependentReportMoveBehindMoreAnalysisOnlyOnAndroid() {
        val activity = monthlyActivity(income = 0L, expense = 1L)
        val android = androidAnalysisLayout(UiPresentationProfile.ANDROID, activity)
        assertFalse(android.keepsTrendInline)
        assertFalse(android.keepsReportInline)
        assertTrue(android.showsMoreAnalysis)

        val legacy = androidAnalysisLayout(UiPresentationProfile.LEGACY, activity)
        assertTrue(legacy.keepsTrendInline)
        assertTrue(legacy.keepsReportInline)
        assertFalse(legacy.showsMoreAnalysis)
    }

    // ---------------------------------------------------------------------------------------------
    // Analysis: the horizontal category ranking
    // ---------------------------------------------------------------------------------------------

    @Test
    fun aSingleCategoryRankingIsExactlyOneHundredPercentOnOneRow() {
        val section =
            androidCategoryShareSections(listOf(categoryTotal("cat-food", "餐饮", 1_234L))).single()

        assertEquals(cny, section.currency)
        assertEquals(1, section.chartRows.size)
        assertEquals("100.0%", section.chartRows.single().percentText())
        assertEquals("餐饮 12.34 CNY 100.0%", categoryShareRowText(section.chartRows.single(), cny))
        assertTrue(section.rendersBars)
        assertNull(section.failure)
    }

    @Test
    fun theRankingIsOrderedByAmountAndKeepsTheExactAmountBesideThePercentage() {
        val section =
            androidCategoryShareSections(
                listOf(
                    categoryTotal("cat-a", "餐饮", 1_000L),
                    categoryTotal("cat-b", "交通", 3_000L),
                ),
            ).single()

        assertEquals(listOf("交通", "餐饮"), section.chartRows.map { it.name })
        assertEquals(listOf("75.0%", "25.0%"), section.chartRows.map { it.percentText() })
        assertEquals("交通 30.00 CNY 75.0%", categoryShareRowText(section.chartRows.first(), cny))
    }

    @Test
    fun aRefusedChartKeepsTheExactTableAndStatesWhyThereIsNoChart() {
        val section = androidCategoryShareSections(listOf(categoryTotal("cat-a", "餐饮", 0L))).single()

        assertFalse(section.rendersBars)
        assertEquals("无法作图：分类金额合计为零。", categoryShareNoChartText(section.failure!!))
        // The exact table row survives the refused chart, with no invented percentage.
        assertEquals(listOf("餐饮"), section.exactRows.map { it.name })
        assertEquals(0L, section.exactRows.single().amountMinorUnits)
    }

    @Test
    fun categoriesWithNoPositiveContributionNeverEnterTheChart() {
        // The real refund-only shape: no positive expense and a refund, so the category's net is
        // negative without inventing a negative posting on the positive side.
        val section =
            androidCategoryShareSections(
                listOf(
                    categoryTotal("cat-a", "餐饮", 1_000L),
                    categoryTotal("cat-b", "退款", positiveMinorUnits = 0L, refundMinorUnits = -500L),
                ),
            ).single()

        // The negative net is NOT filtered out: A's helper refuses the chart (a filtered list would
        // have let 餐饮 claim a misleading 100.0%).
        assertEquals(CategoryShareFailure.NEGATIVE_AMOUNT, section.failure)
        assertTrue(section.chartRows.isEmpty())
        assertFalse(section.rendersBars)
        // Both exact rows stay visible with their signs preserved.
        assertEquals(listOf("餐饮", "退款"), section.exactRows.map { it.name })
        assertEquals(listOf(1_000L, -500L), section.exactRows.map { it.amountMinorUnits })
    }

    @Test
    fun aZeroCategoryStaysInTheExactTableWithoutDrawingAZeroBar() {
        val section =
            androidCategoryShareSections(
                listOf(categoryTotal("cat-a", "餐饮", 1_000L), categoryTotal("cat-b", "交通", 0L)),
            ).single()

        // The positive category is the single chart row at 100.0%; the zero category never becomes a
        // 0.0% bar (it would state nothing) but stays visible with its exact amount.
        assertEquals(listOf("餐饮"), section.chartRows.map { it.name })
        assertEquals("100.0%", section.chartRows.single().percentText())
        assertEquals(listOf("餐饮", "交通"), section.exactRows.map { it.name })
        assertEquals(listOf(1_000L, 0L), section.exactRows.map { it.amountMinorUnits })
        assertNull(section.failure)
    }

    @Test
    fun aZeroOrOverflowingTotalRefusesTheChartWithItsOwnReason() {
        val zero = androidCategoryShareSections(listOf(categoryTotal("cat-a", "餐饮", 0L))).single()
        assertEquals(CategoryShareFailure.ZERO_DENOMINATOR, zero.failure)
        assertEquals(listOf(0L), zero.exactRows.map { it.amountMinorUnits })

        val overflow =
            androidCategoryShareSections(
                listOf(categoryTotal("cat-a", "餐饮", Long.MAX_VALUE), categoryTotal("cat-b", "交通", 1L)),
            ).single()
        assertEquals(CategoryShareFailure.OVERFLOW, overflow.failure)
        assertTrue(overflow.chartRows.isEmpty())
    }

    // ---------------------------------------------------------------------------------------------
    // Calendar: the empty day
    // ---------------------------------------------------------------------------------------------

    @Test
    fun aZeroTransactionDayShowsOnlyItsDateOnAndroid() {
        val day = calendarDay(LocalDate(2026, 11, 3), listOf(calendarRow(cny, income = 0L, expense = 0L)), count = 0)

        assertFalse(calendarDayShowsAmounts(day))
        assertTrue(calendarVisibleCurrencyRows(day, UiPresentationProfile.ANDROID).isEmpty())
        assertNull(calendarDayCountText(day, UiPresentationProfile.ANDROID))
    }

    @Test
    fun aCountedDayWhoseAmountsAreAllZeroKeepsTheCountAndDropsTheZeroAmounts() {
        // count > 0 with every axis at zero: the day IS counted (a real zero amount, not an empty
        // day), so Android keeps the 笔数 line and drops only the zero amount rows.
        val day = calendarDay(LocalDate(2026, 11, 6), listOf(calendarRow(cny, income = 0L, expense = 0L), calendarRow(jpy, 0L, 0L)), count = 2)

        assertTrue(calendarDayShowsAmounts(day))
        assertTrue(calendarVisibleCurrencyRows(day, UiPresentationProfile.ANDROID).isEmpty())
        assertEquals("2 笔", calendarDayCountText(day, UiPresentationProfile.ANDROID))
    }

    @Test
    fun aDayWithRealMovementShowsItsNonZeroAmountsAndItsCount() {
        val day =
            calendarDay(
                LocalDate(2026, 11, 4),
                listOf(calendarRow(cny, income = 0L, expense = 1_234L), calendarRow(jpy, income = 0L, expense = 0L)),
                count = 2,
            )

        assertTrue(calendarDayShowsAmounts(day))
        assertEquals(listOf(cny), calendarVisibleCurrencyRows(day, UiPresentationProfile.ANDROID).map { it.currency })
        assertEquals("2 笔", calendarDayCountText(day, UiPresentationProfile.ANDROID))
    }

    @Test
    fun theLegacyCalendarCellStillShowsEveryAxisCurrencyAndTheCount() {
        val day = calendarDay(LocalDate(2026, 11, 5), listOf(calendarRow(cny, 0L, 0L), calendarRow(jpy, 0L, 0L)), count = 0)
        assertEquals(2, calendarVisibleCurrencyRows(day, UiPresentationProfile.LEGACY).size)
        assertEquals("0 笔", calendarDayCountText(day, UiPresentationProfile.LEGACY))
    }

    // ---------------------------------------------------------------------------------------------
    // Budget: the unconfigured scope
    // ---------------------------------------------------------------------------------------------

    @Test
    fun anUnconfiguredBudgetIsOnlyASuccessfulEmptyMonth() {
        val emptyMonth =
            BudgetMonthViewResult.Success(
                BudgetMonthView(
                    ledgerId = ledgerId,
                    month = YearMonth(2026, 11),
                    catalogVersion = 1L,
                    total = null,
                    categories = emptyList(),
                ),
            )
        // Only a SUCCESSFUL read that states no configured scope is 未配置.
        assertTrue(budgetIsUnconfigured(emptyMonth))
        assertEquals(BudgetNoticeMode.SHORT, budgetNoticeMode(UiPresentationProfile.ANDROID, emptyMonth))
        // Not loaded and both failures keep the full statement — they never claim 未配置.
        assertFalse(budgetIsUnconfigured(null))
        assertEquals(BudgetNoticeMode.FULL, budgetNoticeMode(UiPresentationProfile.ANDROID, null))
        assertFalse(budgetIsUnconfigured(BudgetMonthViewResult.Unavailable))
        assertEquals(BudgetNoticeMode.FULL, budgetNoticeMode(UiPresentationProfile.ANDROID, BudgetMonthViewResult.Unavailable))
        assertFalse(budgetIsUnconfigured(BudgetMonthViewResult.InvalidState))
        // LEGACY always keeps the full explanation.
        assertEquals(BudgetNoticeMode.FULL, budgetNoticeMode(UiPresentationProfile.LEGACY, emptyMonth))
        assertEquals("还没有设置预算。", BUDGET_UNCONFIGURED_SHORT_TEXT)
    }

    // ---------------------------------------------------------------------------------------------
    // Both profiles
    // ---------------------------------------------------------------------------------------------

    @Test
    fun theLegacyProfileKeepsThePreBHomeRowShape() {
        val row = row(TransactionKind.EXPENSE, "午饭", listOf(posting("p1", "acct-asset", -1_234L)))
        assertNull(homeRowAndroidPresentationFor(UiPresentationProfile.LEGACY, row, categorySnapshot(), catalog))
        assertTrue(homeRowAndroidPresentationFor(UiPresentationProfile.ANDROID, row, categorySnapshot(), catalog) != null)
    }

    @Test
    fun everyAndroidDecisionIsInertUnderTheLegacyProfile() {
        assertEquals(false, androidTabPresentation(UiPresentationProfile.LEGACY))
        assertEquals(true, androidTabPresentation(UiPresentationProfile.ANDROID))
        assertEquals(UiPresentationProfile.LEGACY, UiPresentationProfile.entries.first())
    }

    // ---------------------------------------------------------------------------------------------
    // Reducer: the add-only route
    // ---------------------------------------------------------------------------------------------

    @Test
    fun theRouteFlagRoundTripsThroughTheReducerWithoutTouchingTheTabOrTheReadState() {
        val readState = LedgerCurrentState(LedgerId("ledger-test"), emptyList(), emptyList())
        val reducer: P503Reducer = P503ReducerImpl(ParseManualExpenseAmount(), cny)
        val overview = P503AppState.OverviewEmpty(readState, P503Tab.ACCOUNTS)

        val opened = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(overview, P503UiEvent.OpenCatalogManagement))
        assertTrue(opened.catalogManagementSurface)
        assertEquals(P503Tab.ACCOUNTS, opened.selectedTab)
        assertEquals(readState, opened.state)

        val closedByBack = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(opened, P503UiEvent.Back))
        assertFalse(closedByBack.catalogManagementSurface)
        assertEquals(P503Tab.ACCOUNTS, closedByBack.selectedTab)

        val closed = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(opened, P503UiEvent.CloseCatalogManagement))
        assertFalse(closed.catalogManagementSurface)

        val leftByTab = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(opened, P503UiEvent.SelectTab(P503Tab.HOME)))
        assertFalse(leftByTab.catalogManagementSurface)
        assertEquals(P503Tab.HOME, leftByTab.selectedTab)
    }

    @Test
    fun theSecondBackLeavesTheAccountsFirstScreenForHome() {
        val readState = LedgerCurrentState(LedgerId("ledger-test"), emptyList(), emptyList())
        val reducer: P503Reducer = P503ReducerImpl(ParseManualExpenseAmount(), cny)
        val overview = P503AppState.OverviewEmpty(readState, P503Tab.ACCOUNTS)

        val route = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(overview, P503UiEvent.OpenCatalogManagement))
        val accountsFirst = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(route, P503UiEvent.Back))
        assertFalse(accountsFirst.catalogManagementSurface)
        assertEquals(P503Tab.ACCOUNTS, accountsFirst.selectedTab)

        // The first Back only closed the route; the second Back runs the ordinary ACCOUNTS -> HOME
        // rule. Both come from the same state machine, so the gesture and the route button agree.
        val home = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(accountsFirst, P503UiEvent.Back))
        assertEquals(P503Tab.HOME, home.selectedTab)
    }

    @Test
    fun theRouteEventsAreAbsorbedOnSurfacesThatCannotShowTheRoute() {
        val readState = LedgerCurrentState(LedgerId("ledger-test"), emptyList(), emptyList())
        val reducer: P503Reducer = P503ReducerImpl(ParseManualExpenseAmount(), cny)
        val elsewhere = P503AppState.BackupExport(P503AppState.OverviewEmpty(readState))

        assertEquals(elsewhere, reducer.reduce(elsewhere, P503UiEvent.OpenCatalogManagement))
        assertEquals(elsewhere, reducer.reduce(elsewhere, P503UiEvent.CloseCatalogManagement))
    }

    @Test
    fun theLegacyAnalysisScreenKeepsThePieRegionAndTheInlineSections() {
        val activity = monthlyActivity(income = 5_000L, expense = 1_234L)
        val legacy = androidAnalysisLayout(UiPresentationProfile.LEGACY, activity)

        // LEGACY selects the pre-B expense region (P503CategoryRegion withPie = true), never the
        // Android ranking; the mean of `showsExpenseRanking = false` is that the screen's else
        // branch runs.
        assertFalse(legacy.showsExpenseRanking)
        assertTrue(legacy.showsIncomeRegion)
        assertTrue(legacy.showsRawSummary)
        assertTrue(legacy.keepsTrendInline)
        assertTrue(legacy.keepsReportInline)
        assertFalse(legacy.showsMoreAnalysis)

        val android = androidAnalysisLayout(UiPresentationProfile.ANDROID, activity)
        assertTrue(android.showsExpenseRanking)
        assertFalse(android.showsRawSummary)
    }

    @Test
    fun theAndroidRowIsChosenEvenWithoutACatalogAndStatesTheAbsence() {
        val row = row(TransactionKind.EXPENSE, null, listOf(posting("p1", "acct-asset", -1_234L)))

        // The Android decision never falls back to the legacy enum/UTC row: with no catalog the row
        // still comes from A's projection and states 未加载 for the amounts.
        val android = assertNotNull(homeRowAndroidPresentationFor(UiPresentationProfile.ANDROID, row, null, null))
        assertEquals("未加载", android.amountAbsenceText)
        assertTrue(android.amountLines.isEmpty())
        // The title is the Chinese business type, never the enum constant.
        assertEquals("支出", android.title)
        assertFalse(homeRowAndroidContentDescription(android).contains("EXPENSE"))

        // LEGACY is the only profile that renders the pre-B row.
        assertNull(homeRowAndroidPresentationFor(UiPresentationProfile.LEGACY, row, null, null))
    }

    @Test
    fun theCurrentStateFallbackRowUsesTheChineseKindAndLocalTime() {
        // The monthly-not-loaded fallback row has no catalog; the Android face still shows the
        // Chinese business type, the local wall-clock time and exact amount+code — no enum, no UTC.
        val header = currentRowAndroidHeader(TransactionKind.EXPENSE, Instant.parse("2026-11-10T12:00:00Z"))
        assertEquals("支出 · 发生 2026-11-10T20:00", header)
        val line = currentRowAndroidAmountLine("现金", -1_234L, cny)
        assertEquals("现金 -12.34 CNY", line)

        val description = currentRowAndroidContentDescription(header, listOf(line))
        assertFalse(description.contains("EXPENSE"))
        assertFalse(description.contains("Z"))
        assertTrue(description.contains("-12.34 CNY"))
    }

    @Test
    fun aRenamedOrNewlyAddedAccountComesFromTheSessionAuthority() {
        // The construction-time catalog is empty (the "old" state); the session authority knows the
        // account created since. The display projection must read the session's catalog.
        val sessionCatalog =
            assertIs<DomainResult.Success<LedgerCatalog>>(
                LedgerCatalog.create(
                    accounts =
                        listOf(
                            Account(
                                id = AccountId("acct-new"),
                                ledgerId = ledgerId,
                                kind = AccountKind.ASSET,
                                currency = cny,
                                ownedByUser = true,
                                realAccount = true,
                                name = "新账户",
                            ),
                        ),
                    categories = emptyList(),
                ),
            ).value
        val reader = CatalogAuthorityReader { CatalogAuthority(ledgerId, sessionCatalog, 2L) }
        val session = CatalogConsumerSession(reader, ledgerId, reader.load(ledgerId)!!, TestReadPort())
        val facade = minimalP503LedgerFacade(catalogSession = session)

        assertSame(sessionCatalog, facade.currentAuthoritativeCatalog())
        assertEquals(listOf("新账户"), facade.currentAuthoritativeCatalog().accounts.map { it.name })

        val row = row(TransactionKind.EXPENSE, null, listOf(posting("p1", "acct-new", -1_234L)))
        val fromSession = homeRowAndroidPresentation(row, null, facade.currentAuthoritativeCatalog())
        assertEquals(listOf("新账户 -12.34 CNY"), fromSession.amountLines.map { "${it.accountName} ${it.amount.text}" })

        // The construction-time catalog does not know the account: it is an explicit failure (never
        // an invented amount and never a silent empty list).
        assertEquals("读取失败", homeRowAndroidPresentation(row, null, facade.catalog).amountAbsenceText)
    }

    @Test
    fun aSnapshotWithoutItsCatalogIsNotAUsableDisplayPair() {
        val snapshot = categorySnapshot()
        assertTrue(catalogDisplayPairComplete(snapshot, catalog))
        assertFalse(catalogDisplayPairComplete(null, catalog))
        assertFalse(catalogDisplayPairComplete(snapshot, null))
        assertFalse(catalogDisplayPairComplete(null, null))
    }

    @Test
    fun anIncompleteFreshPairNeverOverwritesTheCurrentDisplayPair() {
        val currentSnapshot = categorySnapshot()
        val freshSnapshot = snapshot(listOf(category("cat-new", null, "新分类", "acct-expense")))

        // A fresh snapshot without its catalog must NOT replace the current snapshot alone.
        val snapshotOnly =
            landCatalogDisplayPair(
                currentSnapshot,
                catalog,
                CatalogDisplayPair(freshSnapshot, null),
            )
        assertSame(currentSnapshot, snapshotOnly.snapshot)
        assertSame(catalog, snapshotOnly.catalog)

        // A fresh catalog without its snapshot likewise keeps both current halves.
        val catalogOnly =
            landCatalogDisplayPair(
                currentSnapshot,
                catalog,
                CatalogDisplayPair(null, catalog),
            )
        assertSame(currentSnapshot, catalogOnly.snapshot)
        assertSame(catalog, catalogOnly.catalog)

        val emptyPair =
            landCatalogDisplayPair(
                currentSnapshot,
                catalog,
                CatalogDisplayPair(null, null),
            )
        assertSame(currentSnapshot, emptyPair.snapshot)
        assertSame(catalog, emptyPair.catalog)

        // Only a COMPLETE fresh pair advances the cache.
        val complete =
            landCatalogDisplayPair(
                currentSnapshot,
                catalog,
                CatalogDisplayPair(freshSnapshot, catalog),
            )
        assertSame(freshSnapshot, complete.snapshot)
        assertSame(catalog, complete.catalog)
    }

    @Test
    fun theMoreAnalysisToggleIsAViewPreference() {
        assertEquals("更多分析", moreAnalysisOpenLabel(open = false))
        assertEquals("收起更多分析", moreAnalysisOpenLabel(open = true))
        assertTrue(toggledMoreAnalysis(false))
        assertFalse(toggledMoreAnalysis(true))
    }

    // ---------------------------------------------------------------------------------------------
    // The shared flow row (HOME / search / report drilldown)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun theSharedFlowRowHeaderFollowsTheProfile() {
        val row = row(TransactionKind.EXPENSE, "午饭", listOf(posting("p1", "acct-asset", -1_234L)))

        // LEGACY: the pre-B text verbatim (enum constant + the raw statistics instant).
        assertEquals("EXPENSE · 统计 2026-11-10T12:00:00Z · 午饭", flowRowHeaderText(UiPresentationProfile.LEGACY, row))
        assertEquals(flowRowLegacyHeaderText(row), flowRowHeaderText(UiPresentationProfile.LEGACY, row))

        // ANDROID: Chinese business name + the frozen local wall clock, no enum, no bare UTC.
        assertEquals("支出 · 统计 2026-11-10T20:00 · 午饭", flowRowHeaderText(UiPresentationProfile.ANDROID, row))
        assertFalse(flowRowHeaderText(UiPresentationProfile.ANDROID, row).contains("EXPENSE"))
        assertFalse(flowRowHeaderText(UiPresentationProfile.ANDROID, row).contains("Z"))

        // A note that carries no content is not displayed on Android; LEGACY keeps it verbatim.
        val blankNote = row(TransactionKind.EXPENSE, "   ", listOf(posting("p1", "acct-asset", -1L)))
        assertEquals("支出 · 统计 2026-11-10T20:00", flowRowHeaderText(UiPresentationProfile.ANDROID, blankNote))
        assertEquals("EXPENSE · 统计 2026-11-10T12:00:00Z ·    ", flowRowHeaderText(UiPresentationProfile.LEGACY, blankNote))
    }

    @Test
    fun theSharedFlowRowTalkbackKeepsTheCallersAmountFormatter() {
        // The P806 drilldown passes its own exact formatter (Long.MIN_VALUE is off by one under the
        // legacy one); the Android sentence must keep using it.
        val extreme = row(TransactionKind.EXPENSE, "极值", listOf(posting("p1", "acct-asset", Long.MIN_VALUE)))

        val android = flowRowContentDescriptionFor(UiPresentationProfile.ANDROID, extreme, emptyMap(), ::p806MinorUnitsText)
        assertTrue(android.startsWith("支出，统计时间 2026-11-10T20:00"))
        assertTrue(android.contains("-92233720368547758.08 CNY"))
        assertFalse(android.contains("EXPENSE"))
        assertFalse(android.contains("Z"))

        // LEGACY keeps the pre-B sentence exactly.
        val legacy = flowRowContentDescriptionFor(UiPresentationProfile.LEGACY, extreme, emptyMap())
        assertTrue(legacy.startsWith("EXPENSE，统计时间 2026-11-10T12:00:00Z"))
        assertEquals(flowRowContentDescription(extreme, emptyMap()), legacy)

        // Both profiles keep the caller's per-posting account/label, amount and currency code.
        val named = mapOf(AccountId("acct-asset") to "现金")
        assertEquals(
            "支出，统计时间 2026-11-10T20:00，备注 极值；现金 -92233720368547758.08 CNY",
            flowRowContentDescriptionFor(UiPresentationProfile.ANDROID, extreme, named, ::p806MinorUnitsText),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    private fun category(
        id: String,
        parentId: String?,
        name: String,
        postingAccountId: String?,
    ): CategoryTreeView =
        CategoryTreeView(
            categoryId = CategoryId(id),
            parentId = parentId?.let(::CategoryId),
            name = name,
            kind = CategoryKind.EXPENSE,
            active = true,
            postingAccountId = postingAccountId?.let(::AccountId),
        )

    private fun snapshot(categories: List<CategoryTreeView>): CatalogSnapshotView =
        CatalogSnapshotView(catalogVersion = 1, manageableAccounts = emptyList(), categories = categories)

    private fun categorySnapshot(): CatalogSnapshotView =
        snapshot(
            listOf(
                category("cat-food", null, "餐饮", null),
                category("cat-breakfast", "cat-food", "早餐", "acct-expense"),
            ),
        )

    private fun account(
        id: String,
        kind: AccountKind,
        currency: CurrencyUnit = cny,
    ): Account =
        Account(
            id = AccountId(id),
            ledgerId = ledgerId,
            kind = kind,
            currency = currency,
            ownedByUser = true,
            realAccount = kind == AccountKind.ASSET || kind == AccountKind.LIABILITY,
            name = id,
        )

    private val catalog =
        assertIs<DomainResult.Success<LedgerCatalog>>(
            LedgerCatalog.create(
                accounts =
                    listOf(
                        account("acct-asset", AccountKind.ASSET),
                        account("acct-asset-2", AccountKind.ASSET),
                        account("acct-jpy", AccountKind.ASSET, currency = jpy),
                        account("acct-liability", AccountKind.LIABILITY),
                        account("acct-expense", AccountKind.EXPENSE),
                        account("acct-equity", AccountKind.EQUITY),
                    ),
                categories = emptyList(),
            ),
        ).value

    private fun posting(
        id: String,
        accountId: String,
        minorUnits: Long,
        currency: CurrencyUnit = cny,
    ): Posting = Posting(PostingId(id), AccountId(accountId), Money.ofMinor(minorUnits, currency))

    private fun row(
        kind: TransactionKind,
        note: String?,
        postings: List<Posting>,
    ): LedgerEntryRow =
        LedgerEntryRow(
            transactionId = TransactionId("tx"),
            currentVersionId = TransactionVersionId("v"),
            kind = kind,
            occurredAt = Instant.parse("2026-11-09T12:00:00Z"),
            statisticsAt = Instant.parse("2026-11-10T12:00:00Z"),
            note = note,
            postings = postings,
        )

    private fun categoryTotal(
        id: String,
        name: String,
        positiveMinorUnits: Long,
        refundMinorUnits: Long = 0L,
    ): MonthlyCategoryTotal =
        MonthlyCategoryTotal(
            categoryId = CategoryId(id),
            categoryName = name,
            totals = listOf(MonthlyCategoryCurrencyTotal(cny, positiveMinorUnits = positiveMinorUnits, refundMinorUnits = refundMinorUnits)),
            children = emptyList(),
        )

    private fun monthlyActivity(
        income: Long,
        expense: Long,
    ): MonthlyActivity =
        MonthlyActivity(
            ledgerId = ledgerId,
            month = YearMonth(2026, 11),
            currencies =
                listOf(
                    MonthlyCurrencyActivity(
                        currency = cny,
                        ordinaryIncomeMinorUnits = income,
                        netExpenseMinorUnits = expense,
                        balanceMinorUnits = income - expense,
                        positiveExpenseMinorUnits = expense,
                        refundMinorUnits = 0L,
                        transactionCount = 1,
                        countByKind = mapOf(TransactionKind.EXPENSE to 1),
                    ),
                ),
            expenseCategories = listOf(categoryTotal("cat-food", "餐饮", expense)),
            incomeCategories = if (income == 0L) emptyList() else listOf(categoryTotal("cat-salary", "工资", income)),
        )

    private fun calendarRow(
        currency: CurrencyUnit,
        income: Long,
        expense: Long,
    ): P806CalendarCurrencyView =
        P806CalendarCurrencyView(currency = currency, ordinaryIncomeMinorUnits = income, netExpenseMinorUnits = expense)

    private fun calendarDay(
        date: LocalDate,
        currencies: List<P806CalendarCurrencyView>,
        count: Int,
    ): P806CalendarDayView =
        P806CalendarDayView(
            date = date,
            window = P806Window(startEpochNanos = 0L, endEpochNanos = 1L),
            currencies = currencies,
            transactionCount = count,
        )

    /** The session's read port: no read is reached by these presentation assertions. */
    private class TestReadPort : LedgerCurrentStateReadPort {
        override fun loadCurrentRows(ledgerId: LedgerId): List<CurrentVersionRow> = emptyList()

        override fun findManualExpenseByRequest(
            ledgerId: LedgerId,
            requestId: RequestId,
        ): ManualExpenseCommitRecord? = null

        override fun findManualExpenseByReceipt(
            ledgerId: LedgerId,
            receipt: ConfirmedExpenseReceipt,
        ): ManualExpenseCommitRecord? = null

        override fun findManualIncomeByRequest(
            ledgerId: LedgerId,
            requestId: RequestId,
        ): ManualIncomeCommitRecord? = null

        override fun findManualIncomeByReceipt(
            ledgerId: LedgerId,
            receipt: ConfirmedIncomeReceipt,
        ): ManualIncomeCommitRecord? = null

        override fun findManualTransferByRequest(
            ledgerId: LedgerId,
            requestId: RequestId,
        ): ManualTransferCommitRecord? = null

        override fun findManualTransferByReceipt(
            ledgerId: LedgerId,
            receipt: ConfirmedTransferReceipt,
        ): ManualTransferCommitRecord? = null
    }
}
