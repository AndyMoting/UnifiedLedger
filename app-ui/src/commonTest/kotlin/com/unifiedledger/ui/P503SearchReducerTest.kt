package com.unifiedledger.ui

import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.LedgerEntryRow
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.SearchFailureCode
import com.unifiedledger.application.SearchPage
import com.unifiedledger.application.SearchReadResult
import com.unifiedledger.application.TagMerchantFilterCursor
import com.unifiedledger.application.TagMerchantTagMatchMode
import com.unifiedledger.domain.AccountId
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P8-02 i2a (D7-1..D7-7; spec sections 2.1/2.4/2.5) search surface state-machine evidence — the
 * successor of the retired P7-08 filter state test, keeping every assertion about the filter
 * SEMANTICS that still has a mechanism here (see the i2a report for the moved/dropped ledger).
 *
 * The pins:
 *
 * - the surface opens from the HOME overview carrying the sampled position, dispatches NO read
 *   (D7-6: the guide face until the user submits) and its whole event family is ABSORBED in every
 *   other state — never an ISE (`unhandled`), the "new events never throw" discipline;
 * - the keyword draft is a draft: only `SubmitSearch` commits it and opens the first page;
 * - a condition change is a generation change: it clears the rows, the count, the in-flight marker
 *   and the read-issued flag, so a read issued under the previous condition can never paint rows
 *   under the new one and an unread condition falls back to the guide, never the empty claim;
 * - a landing applies only while its request token is current AND a read is still in flight: a
 *   superseded or duplicated read is dropped whole (the queue's late-write gate);
 * - a typed read failure is carried explicitly and keeps the loaded rows beside it (V-7), and a
 *   failed first page is retried by the same next-page request;
 * - the scroll-restore slot is consumed exactly once; Back restores the exact preserved overview.
 *
 * No facade, no Compose runtime, no clock drift: the reducer is pure and every value is synthetic.
 */
class P503SearchReducerTest {
    private val ledgerId = LedgerId("ledger-search-test")
    private val cny = CurrencyUnit("CNY", 2)
    private val tagA = TagId("tag-a")
    private val merchant = MerchantId("merchant-search-1")
    private val currentState = LedgerCurrentStateForTests.empty(ledgerId)
    private val reducer: P503Reducer =
        P503ReducerImpl(
            ParseManualExpenseAmount(),
            cny,
            LedgerClock { Instant.parse("2026-11-11T00:00:00Z") },
        )

    private fun overview(): P503AppState.OverviewEmpty = P503AppState.OverviewEmpty(state = currentState)

    private fun searchState(): P503AppState.Search = P503AppState.Search(overview = overview())

    private fun entry(
        transactionId: String,
        statisticsAt: String,
    ): LedgerEntryRow =
        LedgerEntryRow(
            transactionId = TransactionId(transactionId),
            currentVersionId = TransactionVersionId("version-$transactionId"),
            kind = TransactionKind.EXPENSE,
            occurredAt = Instant.parse(statisticsAt),
            statisticsAt = Instant.parse(statisticsAt),
            note = null,
            postings =
                listOf(
                    Posting(PostingId("posting-$transactionId-0"), AccountId("account-expense"), Money.ofMinor(1_000L, cny)),
                    Posting(PostingId("posting-$transactionId-1"), AccountId("account-asset"), Money.ofMinor(-1_000L, cny)),
                ),
        )

    private fun successPage(
        vararg entries: LedgerEntryRow,
        hasMore: Boolean = false,
        totalCount: Long? = null,
    ): SearchReadResult.Success =
        SearchReadResult.Success(
            SearchPage(
                entries = entries.toList(),
                hasMore = hasMore,
                nextCursor = if (hasMore) TagMerchantFilterCursor(0L, "cursor") else null,
                totalCount = totalCount ?: entries.size.toLong(),
            ),
        )

    // ---- open / absorb / back ----

    @Test
    fun openEntersTheResultSurfaceFromTheOverviewAndReadsNothing() {
        val opened = assertIs<P503AppState.Search>(reducer.reduce(overview(), P503UiEvent.OpenSearch()))
        assertEquals(currentState, opened.overview.state)
        // D7-6: opening is not a search — the surface starts on its guide face, zero reads.
        assertEquals(0L, opened.requestToken)
        assertTrue(opened.entries.isEmpty())
        assertTrue(!opened.pageLoading)
        assertEquals(SearchFace.GUIDE, searchFace(opened))
    }

    @Test
    fun theWholeEventFamilyIsAbsorbedInEveryOtherState() {
        // Table 6.2a: every family event is state-preserving outside `Search`, so a late or stale
        // dispatch can never reach `unhandled` and turn into an ISE.
        val events =
            listOf(
                P503UiEvent.UpdateSearchKeyword("咖啡"),
                P503UiEvent.SelectSearchWindow(SearchWindowChoice.CURRENT_MONTH),
                P503UiEvent.UpdateSearchCustomStart("2026-11-01T00:00:00Z"),
                P503UiEvent.UpdateSearchCustomEnd("2026-11-30T00:00:00Z"),
                P503UiEvent.ToggleSearchTag(tagA),
                P503UiEvent.SetSearchTagMatch(TagMerchantTagMatchMode.All),
                P503UiEvent.SelectSearchMerchant(merchant),
                P503UiEvent.SubmitSearch,
                P503UiEvent.RequestSearchPage,
                P503UiEvent.SearchPageLanded(1L, SearchReadResult.Failed(SearchFailureCode.SEARCH_UNAVAILABLE)),
            )
        val states =
            listOf<P503AppState>(
                P503AppState.Ready(),
                P503AppState.Created,
                P503AppState.NoChange,
                P503AppState.Recovered,
                overview(),
            )
        for (state in states) {
            for (event in events) {
                assertSame(state, reducer.reduce(state, event), "$event must be absorbed in $state")
            }
        }
        // The open event has ONE designed state: the overview it is opened from.
        val opened = reducer.reduce(overview(), P503UiEvent.OpenSearch())
        assertSame(opened, reducer.reduce(opened, P503UiEvent.OpenSearch()))
        assertSame(P503AppState.Created, reducer.reduce(P503AppState.Created, P503UiEvent.OpenSearch()))
    }

    @Test
    fun backRestoresTheExactPreservedOverview() {
        val opened = assertIs<P503AppState.Search>(reducer.reduce(overview(), P503UiEvent.OpenSearch()))
        assertEquals(overview(), reducer.reduce(opened, P503UiEvent.Back))
    }

    @Test
    fun openingCarriesTheSampledHomePositionIntoTheOverviewItReturnsTo() {
        val sampled = ListScrollPosition(firstVisibleItemIndex = 7, firstVisibleItemScrollOffset = 12)
        val opened = assertIs<P503AppState.Search>(reducer.reduce(overview(), P503UiEvent.OpenSearch(originScroll = sampled)))
        // 返回保滚动位: the sampled position rides the carried overview the back target restores.
        assertEquals(sampled, opened.overview.originListScroll)
        assertEquals(opened.overview, reducer.reduce(opened, P503UiEvent.Back))
    }

    // ---- the keyword draft vs the committed keyword ----

    @Test
    fun theKeywordDraftIsCommittedOnlyOnSubmit() {
        var state: P503AppState.Search = searchState()
        state = assertIs(reducer.reduce(state, P503UiEvent.UpdateSearchKeyword("咖啡")))
        assertEquals("咖啡", state.keywordDraft)
        // Editing the field after a result page must not relabel the rows on screen.
        assertEquals("", state.keyword)
        assertEquals(0L, state.requestToken)

        state = assertIs(reducer.reduce(state, P503UiEvent.SubmitSearch))
        assertEquals("咖啡", state.keyword)
        assertEquals("咖啡", state.keywordDraft)
        assertEquals(1L, state.requestToken)
        assertTrue(state.readIssued)
        assertTrue(state.pageLoading)
    }

    @Test
    fun anUnresolvableWindowRefusesTheSubmitWhole() {
        val draft =
            searchState().copy(
                window = SearchWindowChoice.CUSTOM,
                customStartText = "2026-11-30T00:00:00Z",
                customEndText = "2026-11-01T00:00:00Z",
            )
        // R3: the reducer refuses the same draft the screen's submit gate disables, so the host's
        // early return can never strand a 正在搜索… marker with no read behind it.
        assertSame(draft, reducer.reduce(draft, P503UiEvent.SubmitSearch))
    }

    // ---- condition change = generation change ----

    @Test
    fun aConditionChangeResetsTheLandedPages() {
        val withRows =
            assertIs<P503AppState.Search>(
                reducer.reduce(
                    reducer.reduce(searchState(), P503UiEvent.SubmitSearch),
                    P503UiEvent.SearchPageLanded(
                        1L,
                        successPage(entry("tx-1", "2026-11-01T00:00:00Z"), hasMore = true, totalCount = 9L),
                    ),
                ),
            )
        assertEquals(1, withRows.entries.size)
        assertEquals(9L, withRows.totalCount)

        val toggled = assertIs<P503AppState.Search>(reducer.reduce(withRows, P503UiEvent.ToggleSearchTag(tagA)))
        assertEquals(setOf(tagA), toggled.tagIds)
        // The old condition's rows must never render under the new one.
        assertTrue(toggled.entries.isEmpty())
        assertTrue(!toggled.hasMore)
        assertNull(toggled.nextCursor)
        assertEquals(0L, toggled.totalCount)
        assertTrue(!toggled.pageLoading)

        // The merchant and the window are condition changes of the same kind.
        val merchantSet = assertIs<P503AppState.Search>(reducer.reduce(withRows, P503UiEvent.SelectSearchMerchant(merchant)))
        assertEquals(merchant, merchantSet.merchantId)
        assertTrue(merchantSet.entries.isEmpty())
        val windowSet = assertIs<P503AppState.Search>(reducer.reduce(withRows, P503UiEvent.SelectSearchWindow(SearchWindowChoice.CURRENT_MONTH)))
        assertEquals(SearchWindowChoice.CURRENT_MONTH, windowSet.window)
        assertTrue(windowSet.entries.isEmpty())
    }

    @Test
    fun reTappingAnAlreadySelectedConditionKeepsTheLandedPage() {
        // The screen dispatches these select events on every tap, so a tap of the already-selected
        // chip/radio is a no-op — an unchanged condition must not wipe the page it already shows.
        val landed =
            assertIs<P503AppState.Search>(
                reducer.reduce(
                    reducer.reduce(searchState(), P503UiEvent.SubmitSearch),
                    P503UiEvent.SearchPageLanded(1L, successPage(entry("tx-1", "2026-11-01T00:00:00Z"), totalCount = 1L)),
                ),
            )
        assertSame(landed, reducer.reduce(landed, P503UiEvent.SelectSearchWindow(SearchWindowChoice.LAST_NINETY_DAYS)))
        assertSame(landed, reducer.reduce(landed, P503UiEvent.SetSearchTagMatch(TagMerchantTagMatchMode.Any)))
        assertSame(landed, reducer.reduce(landed, P503UiEvent.SelectSearchMerchant(null)))
    }

    @Test
    fun editingTheCustomWindowResetsTheLandedPages() {
        val custom: P503AppState.Search =
            searchState().copy(
                window = SearchWindowChoice.CUSTOM,
                customStartText = "2026-11-01T00:00:00Z",
                customEndText = "2026-11-30T00:00:00Z",
            )
        val landed =
            assertIs<P503AppState.Search>(
                reducer.reduce(
                    reducer.reduce(custom, P503UiEvent.SubmitSearch),
                    P503UiEvent.SearchPageLanded(
                        1L,
                        successPage(entry("tx-1", "2026-11-01T00:00:00Z"), hasMore = true, totalCount = 9L),
                    ),
                ),
            )
        assertTrue(landed.hasMore)
        // R2: the condition is assembled from these drafts at dispatch time, so a text edit is a
        // generation change: the old cursor must never continue the old window's keyset under the
        // new window.
        val editedStart =
            assertIs<P503AppState.Search>(reducer.reduce(landed, P503UiEvent.UpdateSearchCustomStart("2026-11-02T00:00:00Z")))
        assertEquals("2026-11-02T00:00:00Z", editedStart.customStartText)
        assertTrue(editedStart.entries.isEmpty())
        assertTrue(!editedStart.hasMore)
        assertNull(editedStart.nextCursor)
        assertEquals(0L, editedStart.totalCount)
        val editedEnd =
            assertIs<P503AppState.Search>(reducer.reduce(landed, P503UiEvent.UpdateSearchCustomEnd("2026-11-29T00:00:00Z")))
        assertTrue(editedEnd.entries.isEmpty())
        assertTrue(!editedEnd.hasMore)
        assertNull(editedEnd.nextCursor)
    }

    @Test
    fun aConditionChangeSupersedesAnInFlightRead() {
        // The old filter pinned this with a condition fingerprint; here the in-flight marker and the
        // reset do it: the pre-change read's landing can never paint rows under the new condition.
        val loading = assertIs<P503AppState.Search>(reducer.reduce(searchState(), P503UiEvent.SubmitSearch))
        assertTrue(loading.pageLoading)
        val changed = assertIs<P503AppState.Search>(reducer.reduce(loading, P503UiEvent.SelectSearchWindow(SearchWindowChoice.CURRENT_MONTH)))
        assertTrue(!changed.pageLoading)
        val landed =
            assertIs<P503AppState.Search>(
                reducer.reduce(changed, P503UiEvent.SearchPageLanded(1L, successPage(entry("tx-1", "2026-11-01T00:00:00Z")))),
            )
        assertTrue(landed.entries.isEmpty())
    }

    // ---- pagination and the landing gates ----

    @Test
    fun aMatchingLandingAppendsThePageAndCarriesTheCursorAndTheCount() {
        val afterFirst =
            assertIs<P503AppState.Search>(
                reducer.reduce(
                    reducer.reduce(searchState(), P503UiEvent.SubmitSearch),
                    P503UiEvent.SearchPageLanded(
                        1L,
                        successPage(
                            entry("tx-newer", "2026-11-03T00:00:00Z"),
                            entry("tx-older", "2026-11-01T00:00:00Z"),
                            hasMore = true,
                            totalCount = 3L,
                        ),
                    ),
                ),
            )
        // The port's frozen keyset order is preserved as-is — appending never re-sorts (D7-5).
        assertEquals(listOf("tx-newer", "tx-older"), afterFirst.entries.map { it.transactionId.value })
        assertTrue(afterFirst.hasMore)
        assertEquals(TagMerchantFilterCursor(0L, "cursor"), afterFirst.nextCursor)
        // D7-2: the count is the whole condition group's, recomputed per page, contract unchanged.
        assertEquals(3L, afterFirst.totalCount)
        assertTrue(!afterFirst.pageLoading)

        val afterSecond =
            assertIs<P503AppState.Search>(
                reducer.reduce(
                    reducer.reduce(afterFirst, P503UiEvent.RequestSearchPage),
                    P503UiEvent.SearchPageLanded(2L, successPage(entry("tx-oldest", "2026-10-01T00:00:00Z"), totalCount = 3L)),
                ),
            )
        assertEquals(listOf("tx-newer", "tx-older", "tx-oldest"), afterSecond.entries.map { it.transactionId.value })
        assertTrue(!afterSecond.hasMore)
        assertNull(afterSecond.nextCursor)
    }

    @Test
    fun aDuplicateRequestWhileOneIsInFlightIsAbsorbed() {
        val loading = assertIs<P503AppState.Search>(reducer.reduce(searchState(), P503UiEvent.SubmitSearch))
        val absorbed = assertIs<P503AppState.Search>(reducer.reduce(loading, P503UiEvent.RequestSearchPage))
        // 一次一页: the duplicate adds no page fields and does NOT advance the token, so the
        // in-flight read's landing stays appliable and no second read can double-append.
        assertSame(loading, absorbed)
    }

    @Test
    fun aLandingSupersededByANewerRequestIsDroppedWhole() {
        // A load-more read still in flight when a NEW submit dispatches: the submit advances the
        // token and clears the rows, so the old read's landing can never merge into the fresh page.
        val withPage =
            assertIs<P503AppState.Search>(
                reducer.reduce(
                    reducer.reduce(searchState(), P503UiEvent.SubmitSearch),
                    P503UiEvent.SearchPageLanded(1L, successPage(entry("tx-1", "2026-11-01T00:00:00Z"), hasMore = true)),
                ),
            )
        val loadMoreInFlight = assertIs<P503AppState.Search>(reducer.reduce(withPage, P503UiEvent.RequestSearchPage))
        assertEquals(2L, loadMoreInFlight.requestToken)
        val resubmitted = assertIs<P503AppState.Search>(reducer.reduce(loadMoreInFlight, P503UiEvent.SubmitSearch))
        assertEquals(3L, resubmitted.requestToken)
        assertTrue(resubmitted.entries.isEmpty())
        val stale =
            assertIs<P503AppState.Search>(
                reducer.reduce(resubmitted, P503UiEvent.SearchPageLanded(2L, successPage(entry("tx-old", "2026-10-01T00:00:00Z")))),
            )
        assertTrue(stale.entries.isEmpty())
        assertTrue(stale.pageLoading)
        val fresh =
            assertIs<P503AppState.Search>(
                reducer.reduce(stale, P503UiEvent.SearchPageLanded(3L, successPage(entry("tx-new", "2026-11-02T00:00:00Z")))),
            )
        assertEquals(listOf("tx-new"), fresh.entries.map { it.transactionId.value })
        assertTrue(!fresh.pageLoading)
    }

    // ---- fail-closed (V-7) ----

    @Test
    fun aTypedFailureIsCarriedExplicitlyAndKeepsTheLoadedPages() {
        var state: P503AppState.Search = searchState()
        state =
            assertIs(
                reducer.reduce(
                    reducer.reduce(state, P503UiEvent.SubmitSearch),
                    P503UiEvent.SearchPageLanded(1L, successPage(entry("tx-1", "2026-11-01T00:00:00Z"), hasMore = true)),
                ),
            )
        state =
            assertIs(
                reducer.reduce(
                    reducer.reduce(state, P503UiEvent.RequestSearchPage),
                    P503UiEvent.SearchPageLanded(2L, SearchReadResult.Failed(SearchFailureCode.SEARCH_UNAVAILABLE)),
                ),
            )
        assertEquals(SearchFailureCode.SEARCH_UNAVAILABLE, state.pageFailure)
        // The rows already loaded stay visible beside the banner — never cleared by a failure.
        assertEquals(1, state.entries.size)
        assertTrue(!state.pageLoading)
        // A failed read with rows is still the LIST face (the banner sits above them).
        assertEquals(SearchFace.LIST, searchFace(state))
    }

    @Test
    fun aFailedFirstPageIsRetriedByTheSameNextPageRequest() {
        var state: P503AppState.Search = searchState()
        state =
            assertIs(
                reducer.reduce(
                    reducer.reduce(state, P503UiEvent.SubmitSearch),
                    P503UiEvent.SearchPageLanded(1L, SearchReadResult.Failed(SearchFailureCode.SEARCH_UNAVAILABLE)),
                ),
            )
        assertEquals(SearchFailureCode.SEARCH_UNAVAILABLE, state.pageFailure)
        assertEquals(SearchFace.UNAVAILABLE, searchFace(state))
        // A cursorless FAILED read holds no cursor but its retry must re-run the first page.
        val retried = assertIs<P503AppState.Search>(reducer.reduce(state, P503UiEvent.RequestSearchPage))
        assertTrue(retried.pageLoading)
        assertNull(retried.pageFailure)
        assertEquals(2L, retried.requestToken)
    }

    @Test
    fun aConditionEditedAfterAFailedReadIsUnreadAndShowsTheGuide() {
        var state: P503AppState.Search = searchState()
        state = assertIs(reducer.reduce(state, P503UiEvent.SubmitSearch))
        state =
            assertIs(
                reducer.reduce(state, P503UiEvent.SearchPageLanded(1L, SearchReadResult.Failed(SearchFailureCode.SEARCH_UNAVAILABLE))),
            )
        assertEquals(SearchFace.UNAVAILABLE, searchFace(state))
        val edited = assertIs<P503AppState.Search>(reducer.reduce(state, P503UiEvent.ToggleSearchTag(tagA)))
        // R1: the token is deliberately kept (it is the landing gate), but no read was issued for
        // the new condition, so the face is the guide — never the empty-result claim.
        assertEquals(1L, edited.requestToken)
        assertEquals(SearchFace.GUIDE, searchFace(edited))
    }

    @Test
    fun anExhaustedFailurelessKeysetRequestsNothingMore() {
        val lastPage =
            assertIs<P503AppState.Search>(
                reducer.reduce(
                    reducer.reduce(searchState(), P503UiEvent.SubmitSearch),
                    P503UiEvent.SearchPageLanded(1L, successPage(entry("tx-1", "2026-11-01T00:00:00Z"))),
                ),
            )
        assertNull(lastPage.nextCursor)
        // Nothing more to request: the dispatch is absorbed and no read is launched.
        assertSame(lastPage, reducer.reduce(lastPage, P503UiEvent.RequestSearchPage))
    }

    // ---- the restore slot ----

    @Test
    fun theScrollRestoreSlotIsConsumedExactlyOnce() {
        val withSlot = searchState().copy(listScroll = ListScrollPosition(3, 5))
        val consumed = assertIs<P503AppState.Search>(reducer.reduce(withSlot, P503UiEvent.ListScrollRestored))
        assertNull(consumed.listScroll)
        // A second consume has nothing to clear and returns the same instance (no churn).
        assertSame(consumed, reducer.reduce(consumed, P503UiEvent.ListScrollRestored))
    }
}
