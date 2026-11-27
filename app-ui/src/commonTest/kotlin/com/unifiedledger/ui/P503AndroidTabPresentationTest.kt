package com.unifiedledger.ui

import com.unifiedledger.application.CatalogSnapshotView
import com.unifiedledger.application.CategoryTreeView
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.LedgerEntryRow
import com.unifiedledger.application.MonthlyActivity
import com.unifiedledger.application.MonthlyCategoryCurrencyTotal
import com.unifiedledger.application.MonthlyCategoryTotal
import com.unifiedledger.application.MonthlyCurrencyActivity
import com.unifiedledger.application.P806CalendarCurrencyView
import com.unifiedledger.application.P806CalendarDayView
import com.unifiedledger.application.P806Window
import com.unifiedledger.application.ParseManualExpenseAmount
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
import kotlin.test.assertNull
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
        val presentation =
            homeRowAndroidPresentation(
                row(TransactionKind.EXPENSE, "午饭", listOf(posting("p1", "acct-asset", -1_234L))),
                categorySnapshot(),
                catalog,
            )
        val description = homeRowAndroidContentDescription(presentation)

        assertTrue(description.contains("午饭"))
        assertTrue(description.contains("早餐"))
        assertTrue(description.contains("-12.34 CNY"))
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
        val section =
            androidCategoryShareSections(
                listOf(categoryTotal("cat-a", "餐饮", 1_000L), categoryTotal("cat-b", "退款", -500L)),
            ).single()

        assertEquals(listOf("餐饮"), section.chartRows.map { it.name })
        // The non-positive category stays visible in the exact table (signs never masked).
        assertEquals(listOf("餐饮", "退款"), section.exactRows.map { it.name })
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
    fun anUnconfiguredBudgetKeepsAShortNoteAndTheSettingsEntryOnAndroid() {
        assertEquals(BudgetNoticeMode.SHORT, budgetNoticeMode(UiPresentationProfile.ANDROID, view = null))
        assertEquals(BudgetNoticeMode.FULL, budgetNoticeMode(UiPresentationProfile.LEGACY, view = null))
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
    fun theRouteEventsAreAbsorbedOnSurfacesThatCannotShowTheRoute() {
        val readState = LedgerCurrentState(LedgerId("ledger-test"), emptyList(), emptyList())
        val reducer: P503Reducer = P503ReducerImpl(ParseManualExpenseAmount(), cny)
        val elsewhere = P503AppState.BackupExport(P503AppState.OverviewEmpty(readState))

        assertEquals(elsewhere, reducer.reduce(elsewhere, P503UiEvent.OpenCatalogManagement))
        assertEquals(elsewhere, reducer.reduce(elsewhere, P503UiEvent.CloseCatalogManagement))
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
        netMinorUnits: Long,
    ): MonthlyCategoryTotal =
        MonthlyCategoryTotal(
            categoryId = CategoryId(id),
            categoryName = name,
            totals = listOf(MonthlyCategoryCurrencyTotal(cny, positiveMinorUnits = netMinorUnits, refundMinorUnits = 0L)),
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
}
