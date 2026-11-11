package com.unifiedledger.ui

import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.LedgerEntryRow
import com.unifiedledger.application.SearchFailureCode
import com.unifiedledger.application.SearchTimeWindow
import com.unifiedledger.application.TagMerchantTagMatchMode
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CatalogItem
import com.unifiedledger.domain.CatalogItemKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.MerchantId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TagId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P8-02 i2a presentation vectors for the search result surface, asserted without a Compose harness
 * (the repository forbids the Compose UI test framework in this module — the
 * `P503ReconciliationPresentationTest` / `P8HomeFlowTest` precedent). Every decision the renderer
 * reads is a pure function, so the vectors below pin the copy and the gates themselves.
 *
 * The load-bearing claims: the draft assembles into the SHARED condition contract with the same tag
 * OR/AND and merchant semantics the retired analysis-page filter carried (V-10); an empty keyword is
 * submittable and round-trips as the pure window + tag/merchant filter (D7-6); the window is
 * mandatory, so an invalid custom range cannot be submitted; the guide face is the never-requested
 * surface and the empty face is the ONLY one that may claim an empty result, so no failure code can
 * ever render as "没有找到" (V-7); the two unimplemented dimension chips are visible and permanently
 * disabled (V-12); the surface's whole affordance vocabulary carries no calendar entry (V-11); and
 * the HOME capsule renders only when both the wiring and the interaction gate hold (V-1 / D7-4).
 */
class P503SearchPresentationTest {
    private val ledgerId = LedgerId("ledger-search-presentation")
    private val cny = CurrencyUnit("CNY", 2)
    private val tagA = TagId("tag-a")
    private val tagB = TagId("tag-b")
    private val merchant = MerchantId("merchant-1")
    private val currentState: LedgerCurrentState = LedgerCurrentStateForTests.empty(ledgerId)

    private fun overview(): P503AppState.OverviewEmpty = P503AppState.OverviewEmpty(state = currentState)

    private fun row(transactionId: String): LedgerEntryRow =
        LedgerEntryRow(
            transactionId = TransactionId(transactionId),
            currentVersionId = TransactionVersionId("version-$transactionId"),
            kind = TransactionKind.EXPENSE,
            occurredAt = Instant.parse("2026-11-01T00:00:00Z"),
            statisticsAt = Instant.parse("2026-11-01T00:00:00Z"),
            note = null,
            postings =
                listOf(
                    Posting(PostingId("posting-$transactionId"), AccountId("account-expense"), Money.ofMinor(100L, cny)),
                ),
        )

    private fun catalogItem(
        id: String,
        active: Boolean,
        tombstoned: Boolean,
        kind: CatalogItemKind = CatalogItemKind.TAG,
    ): CatalogItem =
        CatalogItem(
            kind = kind,
            id = id,
            ledgerId = ledgerId,
            name = id,
            active = active,
            tombstoned = tombstoned,
            revision = 1L,
        )

    // ---- V-10: the same filter semantics, reached through the shared contract ----

    @Test
    fun theConditionAreaBuildsTheEquivalentSearchCondition() {
        val state =
            P503AppState.Search(
                overview = overview(),
                window = SearchWindowChoice.CURRENT_MONTH,
                keyword = "咖啡",
                tagIds = setOf(tagA, tagB),
                tagMatch = TagMerchantTagMatchMode.All,
                merchantId = merchant,
            )
        val condition = assertNotNull(state.toSearchCondition())
        // The four items are the SHARED contract's own fields: the same tag id set, the same OR/AND
        // mode and the same optional merchant the retired analysis-page filter carried (V-10), plus
        // the mandatory window of spec section 2.1.
        assertEquals("咖啡", condition.keyword)
        assertEquals(SearchTimeWindow.CurrentMonth, condition.window)
        assertEquals(setOf(tagA, tagB), condition.tagIds)
        assertEquals(TagMerchantTagMatchMode.All, condition.tagMatch)
        assertEquals(merchant, condition.merchantFilterId)
    }

    @Test
    fun anEmptyKeywordDegradesToTheWindowAndTagMerchantFilter() {
        val state = P503AppState.Search(overview = overview(), keyword = "", tagIds = setOf(tagA), merchantId = merchant)
        val condition = assertNotNull(state.toSearchCondition())
        // D7-6 / spec section 2.2 item 4: an empty keyword turns the three keyword arms off and the
        // condition group degrades to the pure window + tag/merchant filter.
        assertEquals("", condition.keyword)
        assertEquals(SearchTimeWindow.LastNinetyDays, condition.window)
        assertEquals(setOf(tagA), condition.tagIds)
        assertEquals(merchant, condition.merchantFilterId)
        assertTrue(searchSubmitEnabled(state), "an empty keyword must be submittable (D7-6)")
    }

    @Test
    fun theCustomWindowMustBeStrictlyIncreasing() {
        val start = "2026-11-01T00:00:00Z"
        val end = "2026-11-30T00:00:00Z"
        assertEquals(
            SearchTimeWindow.Custom(Instant.parse(start), Instant.parse(end)),
            searchWindowOf(SearchWindowChoice.CUSTOM, start, end),
        )
        // Reversed, equal and unparseable input resolve to nothing.
        assertNull(searchWindowOf(SearchWindowChoice.CUSTOM, end, start))
        assertNull(searchWindowOf(SearchWindowChoice.CUSTOM, start, start))
        assertNull(searchWindowOf(SearchWindowChoice.CUSTOM, "", end))
        // ... and an unresolvable draft cannot be submitted at all: the typed window failure is
        // never produced because the action is disabled before it (spec section 2.1).
        val draft =
            P503AppState.Search(
                overview = overview(),
                window = SearchWindowChoice.CUSTOM,
                customStartText = end,
                customEndText = start,
            )
        assertFalse(searchSubmitEnabled(draft))
        assertNull(draft.toSearchCondition())
    }

    @Test
    fun theWindowChoicesAreTheThreeFrozenOnes() {
        // 本月 / 近 90 天 / 自定义起止 — the enum's order IS the render order (spec section 2.1).
        assertEquals(
            listOf("本月", "近 90 天", "自定义起止"),
            SearchWindowChoice.entries.map(::searchWindowLabel),
        )
        // A read in flight also blocks submit (一次一页).
        assertFalse(searchSubmitEnabled(P503AppState.Search(overview = overview(), pageLoading = true)))
    }

    // ---- V-1 / D7-4: the entry gate ----

    @Test
    fun theGuideFaceIsTheNeverRequestedSurface() {
        // D7-6: the surface opens on the guide, never on an empty-result claim.
        assertEquals(SearchFace.GUIDE, searchFace(P503AppState.Search(overview = overview())))
        assertEquals("输入条件后开始搜索", P8_SEARCH_GUIDE_TEXT)
    }

    @Test
    fun theHomeCapsuleRendersOnlyWhenWiredAndInteractive() {
        // D7-4: BOTH gates must hold. The normal home passes both; the retained read-failure face
        // passes neither (it also gets no callback at all), so no capsule opens an unreadable screen.
        assertTrue(p8SearchCapsuleVisible(searchWired = true, interactionsEnabled = true))
        assertFalse(p8SearchCapsuleVisible(searchWired = false, interactionsEnabled = true))
        assertFalse(p8SearchCapsuleVisible(searchWired = true, interactionsEnabled = false))
        assertFalse(p8SearchCapsuleVisible(searchWired = false, interactionsEnabled = false))
    }

    // ---- V-7: a failure is never an empty result ----

    @Test
    fun everyFailureCopyIsTypedAndNoneClaimsAnEmptyResult() {
        SearchFailureCode.entries.forEach { code ->
            val text = searchFailureText(code)
            assertTrue(text.isNotEmpty(), "$code must carry copy")
            assertFalse(text.contains(P8_SEARCH_EMPTY_TEXT), "$code must not reuse the empty-result copy: $text")
            assertFalse(text.contains("没有找到"), "$code must never render «没有找到»: $text")
            assertFalse(text.contains("没有命中"), "$code must never render «没有命中»: $text")
        }
        // Every code has its OWN line: no two failures share one sentence.
        val texts = SearchFailureCode.entries.map(::searchFailureText)
        assertEquals(texts.size, texts.toSet().size)
    }

    @Test
    fun aFailedReadNeverRendersAsAnEmptyResult() {
        val failed = P503AppState.Search(overview = overview(), requestToken = 1L, pageFailure = SearchFailureCode.SEARCH_UNAVAILABLE)
        assertEquals(SearchFace.UNAVAILABLE, searchFace(failed))
        // A SUCCEEDED read with zero rows is the only empty face (readIssued is what a landed read
        // leaves behind; a bare token does not mean a read happened — R1).
        assertEquals(SearchFace.EMPTY, searchFace(P503AppState.Search(overview = overview(), requestToken = 1L, readIssued = true)))
        // A first page in flight is the loading face, not an empty one.
        assertEquals(SearchFace.LOADING, searchFace(P503AppState.Search(overview = overview(), requestToken = 1L, pageLoading = true)))
        // Rows outrank every banner: a failed more-page keeps the loaded list (and its banner).
        val withRows =
            P503AppState.Search(
                overview = overview(),
                requestToken = 2L,
                pageFailure = SearchFailureCode.SEARCH_BAD_CATALOG,
                entries = listOf(row("tx-1")),
            )
        assertEquals(SearchFace.LIST, searchFace(withRows))
    }

    @Test
    fun theFooterFollowsTheSingleFlightAndFailureState() {
        val base = P503AppState.Search(overview = overview())
        assertEquals(FlowFooterState.END, searchFooterState(base))
        assertEquals(FlowFooterState.LOADING, searchFooterState(base.copy(pageLoading = true)))
        assertEquals(FlowFooterState.RETRY, searchFooterState(base.copy(pageFailure = SearchFailureCode.SEARCH_UNAVAILABLE)))
        assertEquals(FlowFooterState.FETCH_MORE, searchFooterState(base.copy(hasMore = true)))
    }

    // ---- V-12: the two unimplemented dimensions stay visible and inert ----

    @Test
    fun theDisabledDimensionChipsAreVisibleAndNotClickable() {
        // Spec section 2.4 / V-12: 账户 and 金额区间 are drawn and never activatable — the chip list
        // is non-empty (visible) and the enabled state is false (not clickable, never silently gone).
        assertEquals(listOf("账户", "金额区间"), P8_SEARCH_DISABLED_CHIPS)
        assertFalse(P8_SEARCH_DISABLED_CHIP_ENABLED)
    }

    // ---- V-11: no calendar on the result surface ----

    @Test
    fun theResultSurfaceCarriesNoCalendarAffordance() {
        val labels = p8SearchAffordanceLabels()
        // Spec section 2.5: the calendar belongs to P8-06. The whole frozen vocabulary is pinned by
        // the exact set below, so an added affordance — a calendar entry included — changes this list
        // and turns the vector red; nothing can slip onto the surface (a dead or out-of-scope entry
        // included) unnoticed.
        assertEquals(
            setOf(
                P8_SEARCH_TITLE,
                P8_SEARCH_KEYWORD_LABEL,
                P8_SEARCH_WINDOW_HEADER,
                P8_SEARCH_TAG_HEADER,
                P8_SEARCH_TAG_MATCH_ANY_LABEL,
                P8_SEARCH_TAG_MATCH_ALL_LABEL,
                P8_SEARCH_MERCHANT_HEADER,
                P8_SEARCH_NO_MERCHANT_LABEL,
                P8_SEARCH_SUBMIT_LABEL,
                P8_SEARCH_RESULTS_HEADER,
                P8_SEARCH_GUIDE_TEXT,
                P8_SEARCH_EMPTY_TEXT,
                P8_SEARCH_CUSTOM_START_LABEL,
                P8_SEARCH_CUSTOM_END_LABEL,
                "本月",
                "近 90 天",
                "自定义起止",
                "账户",
                "金额区间",
            ),
            labels.toSet(),
        )
    }

    // ---- the moved option rule and the OR/AND vocabulary ----

    @Test
    fun theConditionOptionsIncludeDisabledItemsAndExcludeTombstonedOnes() {
        val tagDisabled = catalogItem("tag-disabled", active = false, tombstoned = false)
        val tagTombstoned = catalogItem("tag-dead", active = false, tombstoned = true)
        val tagLive = catalogItem("tag-live", active = true, tombstoned = false)
        // The merchant fixture carries its own namespace, so the merchant leg can catch a merchant
        // regression instead of re-testing the tag rule (R6).
        val merchantDisabled = catalogItem("merchant-disabled", active = false, tombstoned = false, kind = CatalogItemKind.MERCHANT)
        val merchantTombstoned = catalogItem("merchant-dead", active = false, tombstoned = true, kind = CatalogItemKind.MERCHANT)
        val merchantLive = catalogItem("merchant-live", active = true, tombstoned = false, kind = CatalogItemKind.MERCHANT)
        // The rule the retired analysis-page filter applied (P7-08 spec section 5.1) — moved here
        // WITH the surface, so the semantics are the same, not re-invented (V-10).
        assertEquals(
            setOf("tag-disabled", "tag-live"),
            searchTagConditionOptions(listOf(tagDisabled, tagTombstoned, tagLive)).map { it.id }.toSet(),
        )
        assertEquals(
            setOf("merchant-disabled", "merchant-live"),
            searchMerchantConditionOptions(listOf(merchantDisabled, merchantTombstoned, merchantLive)).map { it.id }.toSet(),
        )
    }

    @Test
    fun theTagMatchLabelsKeepTheOrAndDistinction() {
        // The OR/AND distinction the old condition text carried now lives on the two radios.
        assertTrue(searchTagMatchLabel(TagMerchantTagMatchMode.Any).contains("或"))
        assertTrue(searchTagMatchLabel(TagMerchantTagMatchMode.All).contains("与"))
    }
}
