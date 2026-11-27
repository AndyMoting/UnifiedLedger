package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.Category
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * D-369 A presentation vectors: every claim below is a pure fold over anonymous synthetic data.
 * The load-bearing assertions are the exact output shapes (minor units, currency code, Chinese
 * business names, category order, real-leg sums, one-decimal rational shares) and the separation
 * of absent-value states, so no later UI batch has to re-decide them.
 */
class PPresentationProjectionTest {
    private val cny = CurrencyUnit("CNY", 2)
    private val ledgerId = LedgerId("ledger-presentation")

    // -----------------------------------------------------------------------------------------
    // Business names
    // -----------------------------------------------------------------------------------------

    @Test
    fun everyTransactionKindHasOneChineseBusinessName() {
        val names = TransactionKind.entries.map { transactionKindBusinessName(it) }
        assertEquals(TransactionKind.entries.size, names.toSet().size)
        assertTrue(names.all { it.isNotBlank() })
        assertEquals("支出", transactionKindBusinessName(TransactionKind.EXPENSE))
        assertEquals("退款到账", transactionKindBusinessName(TransactionKind.REFUND_RECEIPT))
        assertEquals("信用还款", transactionKindBusinessName(TransactionKind.CREDIT_REPAYMENT))
        assertEquals("储值接入前余额调整", transactionKindBusinessName(TransactionKind.STORED_VALUE_PRE_ACTIVATION_BALANCE_ADJUSTMENT))
    }

    // -----------------------------------------------------------------------------------------
    // Amounts
    // -----------------------------------------------------------------------------------------

    @Test
    fun amountTextIsSignedMinorUnitsPlusTheCurrencyCode() {
        assertEquals("-12.34 CNY", presentationAmountText(-1_234L, cny))
        assertEquals("12.34 CNY", presentationAmountText(1_234L, cny))
        assertEquals("0.00 CNY", presentationAmountText(0L, cny))
        assertEquals("-5 JPY", presentationAmountText(-5L, CurrencyUnit("JPY", 0)))
        assertEquals("0.123456789012345678 KWD", presentationAmountText(123_456_789_012_345_678L, CurrencyUnit("KWD", 18)))
    }

    @Test
    fun longMinValueRendersExactlyWithoutClampingOrFloatingPoint() {
        // Negating Long.MIN_VALUE overflows; the text is derived from the value's own decimal digits.
        assertEquals("-92233720368547758.08 CNY", presentationAmountText(Long.MIN_VALUE, cny))
        assertEquals("92233720368547758.07 CNY", presentationAmountText(Long.MAX_VALUE, cny))
    }

    @Test
    fun presentationAmountKeepsTheExactValueAndItsText() {
        val amount = PresentationAmount(-1_234L, cny)
        assertEquals(-1_234L, amount.minorUnits)
        assertEquals("-12.34 CNY", amount.text)
    }

    // -----------------------------------------------------------------------------------------
    // Times
    // -----------------------------------------------------------------------------------------

    @Test
    fun businessInstantConvertsToTheFrozenReportingZoneForItsOwnRole() {
        // 16:30Z is 2026-11-09 in UTC but already 2026-11-10 00:30 in Asia/Shanghai.
        val instant = Instant.parse("2026-11-09T16:30:00Z")
        val occurred = occurredPresentationTime(instant)
        val statistics = statisticsPresentationTime(instant)

        assertEquals(LocalDate(2026, 11, 10), occurred.localDate())
        assertEquals(LocalDate(2026, 11, 10), statistics.localDate())
        assertEquals("2026-11-10T00:30", occurred.text())
        assertEquals(PresentationTimeRole.OCCURRED, occurred.role)
        assertEquals("发生时间", occurred.role.label)
        assertEquals(PresentationTimeRole.STATISTICS, statistics.role)
        assertEquals("统计时间", statistics.role.label)
        assertNotEquals(occurred.role, statistics.role)
    }

    @Test
    fun bothPersistedInstantsStaySeparatedInOneProjection() {
        val occurred = Instant.parse("2026-11-01T23:30:00Z")
        val statistics = Instant.parse("2026-11-09T16:30:00Z")
        val times = presentationTimes(occurred, statistics)

        assertEquals("2026-11-02T07:30", times.occurred.text())
        assertEquals("2026-11-10T00:30", times.statistics.text())
        assertEquals(occurred, times.occurred.instant)
        assertEquals(statistics, times.statistics.instant)
    }

    @Test
    fun theNeutralWallClockFormatterCrossesTheUtcDayBoundaryWithoutConstructingARole() {
        // 16:00Z is already 00:00 the next Asia/Shanghai day.
        assertEquals("2026-11-28T00:00", presentationWallClockText(Instant.parse("2026-11-27T16:00:00Z")))
        // 15:59Z is still 23:59 on the same Shanghai day.
        assertEquals("2026-11-27T23:59", presentationWallClockText(Instant.parse("2026-11-27T15:59:00Z")))
        // The neutral formatter and the role-bound display agree; the business roles themselves are
        // unchanged by the neutral helper.
        val instant = Instant.parse("2026-11-09T16:30:00Z")
        assertEquals(presentationWallClockText(instant), occurredPresentationTime(instant).text())
        assertEquals(PresentationTimeRole.OCCURRED, occurredPresentationTime(instant).role)
        assertEquals(PresentationTimeRole.STATISTICS, statisticsPresentationTime(instant).role)
    }

    // -----------------------------------------------------------------------------------------
    // Summary labels and absent states
    // -----------------------------------------------------------------------------------------

    @Test
    fun summaryLabelsAreTheFrozenThree() {
        assertEquals("净支出（含退款）", PresentationSummaryLabel.NET_EXPENSE.text)
        assertEquals("普通收入", PresentationSummaryLabel.ORDINARY_INCOME.text)
        assertEquals("结余", PresentationSummaryLabel.BALANCE.text)
    }

    @Test
    fun absenceStatesHaveDistinctCopyAndCannotSilentlyRenderEmpty() {
        val copies =
            listOf(
                presentationValueText(PresentationValue.NotLoaded),
                presentationValueText(PresentationValue.Failed),
                presentationValueText(PresentationValue.NoRecords),
                presentationValueText(PresentationValue.Unclassified),
                presentationValueText(PresentationValue.NotEligible),
            )
        assertEquals(listOf("未加载", "读取失败", "无记录", "未分类", "无对账资格"), copies)
        assertEquals(5, copies.toSet().size)
        assertTrue(copies.all { it.isNotBlank() })

        val zero = PresentationValue.Present(PresentationAmount(0L, cny))
        // The absent-copy renderer accepts only PresentationAbsence, so a present value has no
        // overload to fall into: it cannot silently become an empty string.
        assertTrue(isRealZero(zero))
        assertTrue(!isRealZero(PresentationValue.NoRecords))
        assertTrue(!isRealZero(PresentationValue.Present(PresentationAmount(1L, cny))))
    }

    // -----------------------------------------------------------------------------------------
    // Categories
    // -----------------------------------------------------------------------------------------

    private fun category(
        id: String,
        parentId: String?,
        name: String,
        postingAccountId: String?,
    ): CatalogCategoryFixture = CatalogCategoryFixture(CategoryId(id), parentId?.let(::CategoryId), name, postingAccountId)

    private data class CatalogCategoryFixture(
        val categoryId: CategoryId,
        val parentId: CategoryId?,
        val name: String,
        val postingAccountId: String?,
    )

    private fun snapshot(categories: List<CatalogCategoryFixture>): CatalogSnapshotView =
        CatalogSnapshotView(
            catalogVersion = 7,
            manageableAccounts = emptyList(),
            categories =
                categories.map {
                    CategoryTreeView(
                        categoryId = it.categoryId,
                        parentId = it.parentId,
                        name = it.name,
                        kind = CategoryKind.EXPENSE,
                        active = true,
                        postingAccountId = it.postingAccountId?.let(::AccountId),
                    )
                },
        )

    private fun posting(
        id: String,
        accountId: String,
        minorUnits: Long,
    ): Posting = Posting(PostingId(id), AccountId(accountId), Money.ofMinor(minorUnits, cny))

    @Test
    fun categoriesResolveByPostingAccountAndKeepThePostingOrder() {
        val snapshot =
            snapshot(
                listOf(
                    category("cat-b", null, "餐饮", null),
                    category("cat-a", "cat-b", "早餐", "acct-food"),
                    category("cat-c", null, "交通", null),
                    category("cat-d", "cat-c", "早餐", "acct-transport"),
                ),
            )
        val postings = listOf(posting("p1", "acct-transport", -100L), posting("p2", "acct-food", -200L))

        val resolved = assertIs<CategoryProjection.Resolved>(projectCategories(postings, snapshot, null))

        assertEquals(listOf("cat-d", "cat-a"), resolved.categories.map { it.categoryId.value })
        // Both are second-level 早餐 under different parents, so the parent path disambiguates.
        assertEquals("早餐", resolved.categories[0].name)
        assertEquals("交通 > 早餐", resolved.categories[0].pathText)
        assertEquals("交通 > 早餐", resolved.categories[0].displayName)
        assertEquals("餐饮 > 早餐", resolved.categories[1].displayName)
        assertTrue(!resolved.hasUnclassified)
    }

    @Test
    fun duplicatePostingAccountMappingsResolveToTheSmallerCategoryIdFirstWins() {
        // The larger id is listed first on purpose: a last-wins map would pick it, while the
        // frozen association sorts by CategoryId text and installs first-wins.
        val snapshot =
            snapshot(
                listOf(
                    category("cat-z", null, "后置", "acct-shared"),
                    category("cat-a", null, "前置", "acct-shared"),
                ),
            )

        val resolved =
            assertIs<CategoryProjection.Resolved>(
                projectCategories(listOf(posting("p1", "acct-shared", -100L)), snapshot, null),
            )

        assertEquals(listOf("cat-a"), resolved.categories.map { it.categoryId.value })
        assertEquals("前置", resolved.categories.single().displayName)
        assertTrue(!resolved.hasUnclassified)
    }

    @Test
    fun aUniqueSecondLevelKeepsItsBareNameAndAnUnmappedLegIsNotGuessed() {
        val snapshot =
            snapshot(
                listOf(
                    category("cat-b", null, "餐饮", null),
                    category("cat-a", "cat-b", "早餐", "acct-food"),
                ),
            )
        val resolved =
            assertIs<CategoryProjection.Resolved>(
                projectCategories(
                    listOf(posting("p1", "acct-food", -100L), posting("p2", "acct-unknown", -50L)),
                    snapshot,
                    null,
                ),
            )

        assertEquals(listOf("cat-a"), resolved.categories.map { it.categoryId.value })
        assertEquals("早餐", resolved.categories.single().displayName)
        assertEquals("餐饮 > 早餐", resolved.categories.single().pathText)
        assertTrue(resolved.hasUnclassified)
    }

    @Test
    fun aMissingCategorySourceIsNotLoadedRatherThanAnEmptyResolvedList() {
        assertIs<CategoryProjection.NotLoaded>(
            projectCategories(listOf(posting("p1", "acct-food", -100L)), null, null),
        )
    }

    @Test
    fun theDomainCatalogIsAValidFallbackSource() {
        val catalog =
            assertIs<DomainResult.Success<LedgerCatalog>>(
                LedgerCatalog.create(
                    accounts = emptyList(),
                    categories =
                        listOf(
                            Category(CategoryId("cat-b"), ledgerId, null, null, true, CategoryKind.EXPENSE, "餐饮"),
                            Category(CategoryId("cat-a"), ledgerId, CategoryId("cat-b"), AccountId("acct-food"), true, CategoryKind.EXPENSE, "早餐"),
                        ),
                ),
            ).value
        val resolved =
            assertIs<CategoryProjection.Resolved>(
                projectCategories(listOf(posting("p1", "acct-food", -100L)), null, catalog),
            )

        assertEquals(listOf("cat-a"), resolved.categories.map { it.categoryId.value })
        assertEquals("餐饮 > 早餐", resolved.categories.single().pathText)
    }

    @Test
    fun tintSlotsAreStableAndInsideThePalette() {
        val first = presentationCategoryTintSlot(CategoryId("cat-a"), 8)
        val second = presentationCategoryTintSlot(CategoryId("cat-a"), 8)
        assertEquals(first, second)
        assertTrue(first in 0 until 8)
        assertTrue(presentationCategoryTintSlot(CategoryId("cat-b"), 8) in 0 until 8)
    }

    // -----------------------------------------------------------------------------------------
    // Home row
    // -----------------------------------------------------------------------------------------

    private fun account(
        id: String,
        kind: AccountKind,
        real: Boolean = true,
    ): Account =
        Account(
            id = AccountId(id),
            ledgerId = ledgerId,
            kind = kind,
            currency = cny,
            ownedByUser = true,
            realAccount = real,
            name = id,
        )

    private val catalog =
        assertIs<DomainResult.Success<LedgerCatalog>>(
            LedgerCatalog.create(
                accounts =
                    listOf(
                        account("acct-asset", AccountKind.ASSET),
                        account("acct-liability", AccountKind.LIABILITY),
                        account("acct-equity", AccountKind.EQUITY),
                        account("acct-expense", AccountKind.EXPENSE, real = false),
                    ),
                categories = emptyList(),
            ),
        ).value

    private fun row(
        kind: TransactionKind,
        note: String?,
        postings: List<Posting>,
        occurredAt: String = "2026-11-09T12:00:00Z",
        statisticsAt: String = "2026-11-10T12:00:00Z",
    ): LedgerEntryRow =
        LedgerEntryRow(
            transactionId = TransactionId("tx"),
            currentVersionId = TransactionVersionId("v"),
            kind = kind,
            occurredAt = Instant.parse(occurredAt),
            statisticsAt = Instant.parse(statisticsAt),
            note = note,
            postings = postings,
        )

    private fun categorySnapshot(): CatalogSnapshotView =
        snapshot(
            listOf(
                category("cat-food", null, "餐饮", null),
                category("cat-breakfast", "cat-food", "早餐", "acct-expense"),
                category("cat-transport", null, "交通", null),
                category("cat-taxi", "cat-transport", "打车", "acct-transport"),
            ),
        )

    private val multiCategoryCatalog =
        assertIs<DomainResult.Success<LedgerCatalog>>(
            LedgerCatalog.create(
                accounts =
                    listOf(
                        account("acct-asset", AccountKind.ASSET),
                        account("acct-expense", AccountKind.EXPENSE, real = false),
                        account("acct-transport", AccountKind.EXPENSE, real = false),
                    ),
                categories = emptyList(),
            ),
        ).value

    @Test
    fun titlePrefersANonBlankNoteThenTheSingleCategoryThenTheBusinessType() {
        val postings = listOf(posting("p1", "acct-expense", 1_000L))

        val noted = projectHomeRow(row(TransactionKind.EXPENSE, "买了早餐", postings), categorySnapshot(), catalog)
        assertEquals("买了早餐", noted.title)
        assertEquals(HomeTitleSource.NOTE, noted.titleSource)

        val blankNote = projectHomeRow(row(TransactionKind.EXPENSE, "   ", postings), categorySnapshot(), catalog)
        assertEquals("早餐", blankNote.title)
        assertEquals(HomeTitleSource.CATEGORY, blankNote.titleSource)

        val multi =
            projectHomeRow(
                row(
                    TransactionKind.EXPENSE,
                    null,
                    listOf(
                        posting("p1", "acct-expense", 1_000L),
                        posting("p2", "acct-transport", 234L),
                        posting("p3", "acct-asset", -1_234L),
                    ),
                ),
                categorySnapshot(),
                multiCategoryCatalog,
            )
        assertEquals("支出", multi.title)
        assertEquals(HomeTitleSource.BUSINESS_TYPE, multi.titleSource)
    }

    @Test
    fun badgesFollowThePostingOrderAndCapAtThreeWithTheRemainderStated() {
        val snapshot =
            snapshot(
                listOf(
                    category("cat-1", null, "一", "acct-1"),
                    category("cat-2", null, "二", "acct-2"),
                    category("cat-3", null, "三", "acct-3"),
                    category("cat-4", null, "四", "acct-4"),
                ),
            )
        val row =
            row(
                TransactionKind.EXPENSE,
                null,
                listOf(posting("p1", "acct-3", -1L), posting("p2", "acct-1", -1L), posting("p3", "acct-4", -1L), posting("p4", "acct-2", -1L)),
            )

        val projection = projectHomeRow(row, snapshot, catalog)

        assertEquals(listOf("cat-3", "cat-1", "cat-4"), projection.categoryBadges.map { it.categoryId.value })
        assertEquals(1, projection.remainingCategoryCount)
    }

    @Test
    fun ordinaryRowsKeepOneLinePerRealLegAndExcludeHiddenLegs() {
        val row =
            row(
                TransactionKind.EXPENSE,
                null,
                listOf(
                    posting("p1", "acct-asset", -1_000L),
                    posting("p2", "acct-liability", -234L),
                    posting("p3", "acct-expense", 1_234L),
                ),
            )

        val amounts = assertIs<HomeAmountProjection.Lines>(projectHomeRow(row, categorySnapshot(), catalog).amounts)

        // Two real legs are never summed into one -12.34 line; each keeps its account.
        assertEquals(listOf(-1_000L, -234L), amounts.lines.map { it.amount.minorUnits })
        assertEquals(listOf("acct-asset", "acct-liability"), amounts.lines.map { it.accountId.value })
        assertEquals(listOf("acct-asset", "acct-liability"), amounts.lines.map { it.accountName })
        assertEquals("-10.00 CNY", amounts.lines[0].amount.text)
        assertEquals("-2.34 CNY", amounts.lines[1].amount.text)
    }

    @Test
    fun aMixedPaymentKeepsEachRealAccountAndItsSignedAmount() {
        val mixedCatalog =
            assertIs<DomainResult.Success<LedgerCatalog>>(
                LedgerCatalog.create(
                    accounts =
                        listOf(
                            account("acct-asset", AccountKind.ASSET),
                            account("acct-asset-2", AccountKind.ASSET),
                            account("acct-expense", AccountKind.EXPENSE, real = false),
                        ),
                    categories = emptyList(),
                ),
            ).value
        val row =
            row(
                TransactionKind.EXPENSE,
                null,
                listOf(
                    posting("p1", "acct-asset", -600L),
                    posting("p2", "acct-asset-2", -400L),
                    posting("p3", "acct-expense", 1_000L),
                ),
            )

        val amounts = assertIs<HomeAmountProjection.Lines>(projectHomeRow(row, categorySnapshot(), mixedCatalog).amounts)

        // Two real CNY assets: two exact lines, in posting order, with their own identities.
        assertEquals(listOf(-600L, -400L), amounts.lines.map { it.amount.minorUnits })
        assertEquals(listOf("acct-asset", "acct-asset-2"), amounts.lines.map { it.accountId.value })
        assertEquals("-6.00 CNY", amounts.lines[0].amount.text)
        assertEquals("-4.00 CNY", amounts.lines[1].amount.text)
        assertEquals(2, amounts.lines.size)
    }

    @Test
    fun aTransferKeepsOneLinePerRealLegInsteadOfAPseudoNet() {
        val row =
            row(
                TransactionKind.ACCOUNT_TRANSFER,
                null,
                listOf(
                    posting("p1", "acct-asset", -500L),
                    posting("p2", "acct-liability", 500L),
                ),
            )

        val amounts = assertIs<HomeAmountProjection.Lines>(projectHomeRow(row, categorySnapshot(), catalog).amounts)

        assertEquals(listOf(-500L, 500L), amounts.lines.map { it.amount.minorUnits })
        assertEquals(listOf("acct-asset", "acct-liability"), amounts.lines.map { it.accountId.value })
        assertEquals(2, amounts.lines.size)
    }

    @Test
    fun aBalanceAdjustmentWithAssetAndEquityLegsCarriesNoCategoryObligation() {
        val row =
            row(
                TransactionKind.BALANCE_ADJUSTMENT,
                null,
                listOf(
                    posting("p1", "acct-asset", -500L),
                    posting("p2", "acct-equity", 500L),
                ),
            )

        val projection = projectHomeRow(row, snapshot(emptyList()), catalog)

        assertTrue(!projection.hasUnclassified)
        assertTrue(projection.categoryBadges.isEmpty())
        assertEquals("余额调整", projection.title)
        assertEquals(HomeTitleSource.BUSINESS_TYPE, projection.titleSource)
    }

    @Test
    fun mixedCurrenciesStayOnSeparateLines() {
        val row =
            row(
                TransactionKind.EXPENSE,
                null,
                listOf(
                    posting("p1", "acct-asset", -100L),
                    Posting(PostingId("p2"), AccountId("acct-asset"), Money.ofMinor(-200L, CurrencyUnit("USD", 2))),
                ),
            )

        val amounts = assertIs<HomeAmountProjection.Lines>(projectHomeRow(row, categorySnapshot(), catalog).amounts)

        assertEquals(listOf(CurrencyUnit("CNY", 2), CurrencyUnit("USD", 2)), amounts.lines.map { it.amount.currency })
    }

    @Test
    fun aRealPaymentLegCarriesNoCategoryObligationSoTheHiddenCategoryStillNamesTheRow() {
        // V-A5/V-A6: the ordinary expense is a hidden-category leg (+12.34 on 早餐's posting
        // account) plus the real payment leg (-12.34 on a real ASSET account). The real leg has no
        // category by design and must not mark the row 未分类, so a no-note row takes 早餐.
        val row =
            row(
                TransactionKind.EXPENSE,
                null,
                listOf(
                    posting("p1", "acct-expense", 1_234L),
                    posting("p2", "acct-asset", -1_234L),
                ),
            )

        val projection = projectHomeRow(row, categorySnapshot(), catalog)

        assertEquals("早餐", projection.title)
        assertEquals(HomeTitleSource.CATEGORY, projection.titleSource)
        assertEquals(listOf("cat-breakfast"), projection.categoryBadges.map { it.categoryId.value })
        assertTrue(!projection.hasUnclassified)
        val amounts = assertIs<HomeAmountProjection.Lines>(projection.amounts)
        assertEquals(listOf("acct-asset"), amounts.lines.map { it.accountId.value })
    }

    @Test
    fun anActuallyUnmappedCategoryLegStaysUnclassifiedAndDropsToTheBusinessType() {
        val row =
            row(
                TransactionKind.EXPENSE,
                null,
                listOf(
                    posting("p1", "acct-expense", 1_234L),
                    posting("p2", "acct-asset", -1_234L),
                    posting("p3", "acct-unmapped-expense", -1L),
                ),
            )
        val unmappedCatalog =
            assertIs<DomainResult.Success<LedgerCatalog>>(
                LedgerCatalog.create(
                    accounts =
                        listOf(
                            account("acct-asset", AccountKind.ASSET),
                            account("acct-expense", AccountKind.EXPENSE, real = false),
                            account("acct-unmapped-expense", AccountKind.EXPENSE, real = false),
                        ),
                    categories = emptyList(),
                ),
            ).value

        val projection = projectHomeRow(row, categorySnapshot(), unmappedCatalog)

        assertTrue(projection.hasUnclassified)
        assertEquals("支出", projection.title)
        assertEquals(HomeTitleSource.BUSINESS_TYPE, projection.titleSource)
    }

    @Test
    fun aMissingCatalogIsNotLoadedAndAnUnknownAccountIsAnExplicitFailure() {
        val row = row(TransactionKind.EXPENSE, null, listOf(posting("p1", "acct-asset", -100L)))
        assertIs<HomeAmountProjection.NotLoaded>(projectHomeRow(row, categorySnapshot(), null).amounts)

        val unknownCatalog =
            assertIs<DomainResult.Success<LedgerCatalog>>(LedgerCatalog.create(emptyList(), emptyList())).value
        assertIs<HomeAmountProjection.Failed>(projectHomeRow(row, categorySnapshot(), unknownCatalog).amounts)
    }

    @Test
    fun theHomeRowCarriesBothTimesWithTheirOwnRoles() {
        val projection = projectHomeRow(row(TransactionKind.EXPENSE, null, emptyList()), null, catalog)
        assertEquals(PresentationTimeRole.OCCURRED, projection.times.occurred.role)
        assertEquals(PresentationTimeRole.STATISTICS, projection.times.statistics.role)
        assertEquals("2026-11-09T20:00", projection.times.occurred.text())
        assertEquals("2026-11-10T20:00", projection.times.statistics.text())
    }

    // -----------------------------------------------------------------------------------------
    // Category shares
    // -----------------------------------------------------------------------------------------

    private fun share(
        id: String,
        amount: Long,
    ): CategoryShareInput = CategoryShareInput(CategoryId(id), id, amount)

    @Test
    fun aSingleCategoryIsExactlyOneHundredPercent() {
        val chart = assertIs<CategoryShareProjection.Chart>(projectCategoryShares(listOf(share("cat-a", 1_234L))))
        assertEquals(1_000L, chart.rows.single().tenthsOfPercent)
        assertEquals("100.0%", chart.rows.single().percentText())
        assertEquals(1_234L, chart.denominatorMinorUnits)
    }

    @Test
    fun sharesUseFixedOneDecimalHalfUpRoundingWithoutFloatingPoint() {
        val chart =
            assertIs<CategoryShareProjection.Chart>(
                projectCategoryShares(listOf(share("a", 1L), share("b", 2L))),
            )

        // 1/3 = 33.333…% -> 33.3%, 2/3 = 66.666…% -> 66.7%; halves round up (1/8 = 12.5%).
        assertEquals(listOf("66.7%", "33.3%"), chart.rows.map { it.percentText() })
        val eighth =
            assertIs<CategoryShareProjection.Chart>(projectCategoryShares(listOf(share("a", 1L), share("b", 7L))))
        assertEquals(listOf("87.5%", "12.5%"), eighth.rows.map { it.percentText() })
    }

    @Test
    fun sharesAreOrderedByAmountThenStableId() {
        val chart =
            assertIs<CategoryShareProjection.Chart>(
                projectCategoryShares(listOf(share("cat-z", 10L), share("cat-a", 10L), share("cat-m", 30L))),
            )
        assertEquals(listOf("cat-m", "cat-a", "cat-z"), chart.rows.map { it.categoryId.value })
    }

    @Test
    fun noChartResultsAreExplicitAndKeepTheExactTableAvailableToTheCaller() {
        assertIs<CategoryShareProjection.NoCategories>(projectCategoryShares(emptyList()))
        assertIs<CategoryShareProjection.NoChart>(
            projectCategoryShares(listOf(share("a", -1L))),
        )
        assertIs<CategoryShareProjection.NoChart>(
            projectCategoryShares(listOf(share("a", 0L), share("b", 0L))),
        )
        val overflow =
            assertIs<CategoryShareProjection.NoChart>(
                projectCategoryShares(listOf(share("a", Long.MAX_VALUE), share("b", 1L))),
            )
        assertEquals(CategoryShareFailure.OVERFLOW, overflow.reason)
    }

    @Test
    fun anOverflowingScaledRemainderIsRefusedInsteadOfWrapping() {
        // denominator is large and the remainder has no room for *1000 + half: the helper returns null.
        val largeDenominator = Long.MAX_VALUE / 2 + 1
        val result = projectCategoryShares(listOf(share("a", 1L), share("b", largeDenominator - 1L)))
        assertIs<CategoryShareProjection.NoChart>(result)
    }
}
