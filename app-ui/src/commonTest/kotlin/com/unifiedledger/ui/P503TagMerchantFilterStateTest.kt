package com.unifiedledger.ui

import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.LedgerEntryRow
import com.unifiedledger.application.TagMerchantFilterCondition
import com.unifiedledger.application.TagMerchantFilterCursor
import com.unifiedledger.application.TagMerchantFilterEntry
import com.unifiedledger.application.TagMerchantFilterFailureCode
import com.unifiedledger.application.TagMerchantFilterPage
import com.unifiedledger.application.TagMerchantFilterReadResult
import com.unifiedledger.application.TagMerchantTagMatchMode
import com.unifiedledger.application.tagMerchantFilterConditionFingerprint
import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.MerchantId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TagId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-08 08.C filter state machine evidence (P708-A07 筛选/代际腿 intent; the vector registration
 * belongs to the main agent). The pins:
 *
 * - the surface opens from the overview with the month seeded, and its events are ABSORBED in
 *   every other state (no ISE — the "new events never throw" discipline);
 * - a condition change resets the accumulated results (results of the old condition never
 *   render under the new one) and a merchant change drops the accumulation;
 * - a landing applies ONLY when its condition fingerprint matches the live condition — an
 *   old-condition return is dropped whole (spec section 5.3's 代际门), and the accumulation
 *   landing is additionally month-gated;
 * - a typed read failure is carried as the explicit failure (fail-closed), never rendered as
 *   "no matches", and the already-loaded pages stay visible next to the banner;
 * - Back restores the exact preserved overview.
 */
class P503TagMerchantFilterStateTest {
    private val ledgerId = LedgerId("ledger-08c-ui")
    private val cny = CurrencyUnit("CNY", 2)
    private val tagA = TagId("tag-a")
    private val tagB = TagId("tag-b")
    private val merchant = MerchantId("merchant-1")
    private val currentState = LedgerCurrentStateForTests.empty(ledgerId)
    private val overview = P503AppState.OverviewEmpty(state = currentState)
    private val march = YearMonth(2026, 3)
    private val reducer: P503Reducer =
        P503ReducerImpl(
            com.unifiedledger.application.ParseManualExpenseAmount(),
            cny,
            LedgerClock { Instant.parse("2026-03-15T00:00:00Z") },
            com.unifiedledger.application.EntryExpressionEvaluator(),
        )

    private fun filterState(
        condition: TagMerchantFilterCondition = TagMerchantFilterCondition(),
    ): P503AppState.TagMerchantFilter = P503AppState.TagMerchantFilter(overview = overview, accumulationMonth = march, condition = condition)

    private fun entry(
        transactionId: String,
        occurredAt: Instant,
        tagIds: List<TagId> = emptyList(),
    ): TagMerchantFilterEntry =
        TagMerchantFilterEntry(
            row =
                LedgerEntryRow(
                    transactionId = TransactionId(transactionId),
                    currentVersionId = TransactionVersionId("version-$transactionId"),
                    kind = TransactionKind.EXPENSE,
                    occurredAt = occurredAt,
                    statisticsAt = occurredAt,
                    note = null,
                    postings =
                        listOf(
                            Posting(PostingId("posting-$transactionId-0"), AccountId("account-expense"), Money.ofMinor(1_000L, cny)),
                            Posting(PostingId("posting-$transactionId-1"), AccountId("account-asset"), Money.ofMinor(-1_000L, cny)),
                        ),
                ),
            tagIds = tagIds,
            merchantId = null,
        )

    private fun successPage(
        vararg entries: TagMerchantFilterEntry,
        hasMore: Boolean = false,
    ): TagMerchantFilterReadResult.Success =
        TagMerchantFilterReadResult.Success(
            TagMerchantFilterPage(
                catalogItemVersion = 1L,
                entries = entries.toList(),
                hasMore = hasMore,
                nextCursor = if (hasMore) TagMerchantFilterCursor(0L, "cursor") else null,
                catalog = catalog(),
            ),
        )

    private fun fingerprint(condition: TagMerchantFilterCondition): String = tagMerchantFilterConditionFingerprint(ledgerId, condition)

    // ---- open / absorb ----

    @Test
    fun openEntersTheSurfaceFromTheOverviewAndIsAbsorbedElsewhere() {
        val opened = assertIs<P503AppState.TagMerchantFilter>(reducer.reduce(overview, P503UiEvent.OpenTagMerchantFilter(march)))
        assertEquals(march, opened.accumulationMonth)
        assertEquals(overview.state, opened.overview.state)
        // Absorbed in every other state: no ISE, no transition.
        val analysis = overview.copy(selectedTab = P503Tab.ANALYSIS)
        assertEquals(analysis, reducer.reduce(analysis, P503UiEvent.ToggleFilterTag(tagA)))
    }

    @Test
    fun backRestoresTheExactPreservedOverview() {
        val opened = assertIs<P503AppState.TagMerchantFilter>(reducer.reduce(overview, P503UiEvent.OpenTagMerchantFilter(march)))
        assertEquals(overview, reducer.reduce(opened, P503UiEvent.Back))
    }

    // ---- condition change resets the results (spec 5.3) ----

    @Test
    fun aConditionChangeResetsTheLoadedPages() {
        val withResults =
            assertIs<P503AppState.TagMerchantFilter>(
                reducer.reduce(
                    reducer.reduce(
                        filterState(),
                        P503UiEvent.TagMerchantFilterPageLanded(fingerprint(TagMerchantFilterCondition()), 0L, successPage(entry("tx-1", Instant.parse("2026-03-01T00:00:00Z")))),
                    ),
                    P503UiEvent.TagMerchantFilterPageLanded(
                        fingerprint(TagMerchantFilterCondition(tagIds = setOf(tagA))),
                        0L,
                        successPage(entry("tx-2", Instant.parse("2026-03-02T00:00:00Z"))),
                    ),
                ),
            )
        // Toggle adds the tag and CLEARS the accumulated entries (the old-condition rows must
        // never render under the new condition).
        val toggled = assertIs<P503AppState.TagMerchantFilter>(reducer.reduce(withResults, P503UiEvent.ToggleFilterTag(tagA)))
        assertEquals(setOf(tagA), toggled.condition.tagIds)
        assertTrue(toggled.loadedEntries.isEmpty())
        assertTrue(!toggled.hasMore)
        assertNull(toggled.nextCursor)
    }

    @Test
    fun aMerchantChangeAlsoDropsTheAccumulation() {
        val withMerchant = filterState(condition = TagMerchantFilterCondition(merchantId = merchant))
        val landed =
            assertIs<P503AppState.TagMerchantFilter>(
                reducer.reduce(
                    withMerchant,
                    P503UiEvent.MerchantAccumulationLanded(fingerprint(withMerchant.condition), 0L, march, successAccumulation()),
                ),
            )
        assertEquals(merchant, landed.condition.merchantId)
        val cleared = assertIs<P503AppState.TagMerchantFilter>(reducer.reduce(landed, P503UiEvent.SetFilterMerchant(null)))
        assertNull(cleared.condition.merchantId)
        assertNull(cleared.accumulation)
    }

    // ---- the fingerprint generation gate (spec 5.3) ----

    @Test
    fun anOldConditionLandingIsDroppedWhole() {
        val liveCondition = TagMerchantFilterCondition(tagIds = setOf(tagA))
        val oldFingerprint = fingerprint(TagMerchantFilterCondition(tagIds = setOf(tagB)))
        val state = filterState(condition = liveCondition)
        val landed =
            assertIs<P503AppState.TagMerchantFilter>(
                reducer.reduce(state, P503UiEvent.TagMerchantFilterPageLanded(oldFingerprint, 0L, successPage(entry("tx-old", Instant.parse("2026-03-01T00:00:00Z"))))),
            )
        // The old-condition return never overwrites the page.
        assertTrue(landed.loadedEntries.isEmpty())
        assertTrue(!landed.pageLoading)
    }

    @Test
    fun aMatchingFingerprintAppendsAndSortsAndCarriesTheCursor() {
        val condition = TagMerchantFilterCondition(tagIds = setOf(tagA))
        val state = filterState(condition = condition)
        val page1 =
            successPage(
                entry("tx-older", Instant.parse("2026-03-01T00:00:00Z"), tagIds = listOf(tagA)),
                entry("tx-newer", Instant.parse("2026-03-03T00:00:00Z"), tagIds = listOf(tagA)),
                hasMore = true,
            )
        val afterFirst =
            assertIs<P503AppState.TagMerchantFilter>(
                reducer.reduce(
                    reducer.reduce(state, P503UiEvent.RequestTagMerchantFilterPage(replace = true)),
                    P503UiEvent.TagMerchantFilterPageLanded(fingerprint(condition), 1L, page1),
                ),
            )
        assertEquals(listOf("tx-newer", "tx-older"), afterFirst.loadedEntries.map { it.row.transactionId.value })
        assertTrue(afterFirst.hasMore)
        assertEquals(TagMerchantFilterCursor(0L, "cursor"), afterFirst.nextCursor)
        // A continuation page appends and the accumulated list re-sorts by the frozen display order.
        val page2 = successPage(entry("tx-middle", Instant.parse("2026-03-02T00:00:00Z"), tagIds = listOf(tagA)))
        val afterSecond =
            assertIs<P503AppState.TagMerchantFilter>(
                reducer.reduce(
                    afterFirst,
                    P503UiEvent.TagMerchantFilterPageLanded(fingerprint(condition), 1L, page2),
                ),
            )
        assertEquals(
            listOf("tx-newer", "tx-middle", "tx-older"),
            afterSecond.loadedEntries.map { it.row.transactionId.value },
        )
        assertTrue(!afterSecond.hasMore)
        assertNull(afterSecond.nextCursor)
    }

    // ---- fail-closed (spec 5.3) ----

    @Test
    fun aTypedFailureIsCarriedExplicitlyAndKeepsTheLoadedPages() {
        val condition = TagMerchantFilterCondition(tagIds = setOf(tagA))
        var state: P503AppState.TagMerchantFilter = filterState(condition = condition)
        state =
            assertIs(
                reducer.reduce(
                    reducer.reduce(state, P503UiEvent.RequestTagMerchantFilterPage(replace = true)),
                    P503UiEvent.TagMerchantFilterPageLanded(fingerprint(condition), 1L, successPage(entry("tx-1", Instant.parse("2026-03-01T00:00:00Z")))),
                ),
            )
        state =
            assertIs(
                reducer.reduce(
                    reducer.reduce(state, P503UiEvent.RequestTagMerchantFilterPage(replace = false)),
                    P503UiEvent.TagMerchantFilterPageLanded(fingerprint(condition), 2L, TagMerchantFilterReadResult.Failed(TagMerchantFilterFailureCode.FILTER_UNAVAILABLE)),
                ),
            )
        assertEquals(TagMerchantFilterFailureCode.FILTER_UNAVAILABLE, state.pageFailure)
        assertEquals(1, state.loadedEntries.size)
        assertTrue(!state.pageLoading)
    }

    @Test
    fun aPageRequestIsAbsorbedWhileOneIsInFlight() {
        val loading = assertIs<P503AppState.TagMerchantFilter>(reducer.reduce(filterState(), P503UiEvent.RequestTagMerchantFilterPage(replace = true)))
        assertTrue(loading.pageLoading)
        val absorbed =
            assertIs<P503AppState.TagMerchantFilter>(reducer.reduce(loading, P503UiEvent.RequestTagMerchantFilterPage(replace = false)))
        // 一次一页: the absorbed append adds no page fields — but the request token STILL
        // advances (fix round P3-1), so the duplicate read's landing can never double-append.
        assertEquals(loading.loadedEntries, absorbed.loadedEntries)
        assertTrue(absorbed.pageLoading)
        assertEquals(loading.pageRequestToken + 1, absorbed.pageRequestToken)
    }

    // ---- the request-token gate (fix round P3-1; ablation: removing the token check turns
    // both tests below red — the stale landing would append) ----

    @Test
    fun aDuplicateSameConditionReadLandingIsRejectedByItsStaleRequestToken() {
        // Vector ①: a same-frame double tap of 加载更多 dispatches two identical page requests;
        // each dispatch advances the token, so only the LAST read's landing may apply and the
        // accumulated page can never double-append.
        val condition = TagMerchantFilterCondition(tagIds = setOf(tagA))
        val fp = fingerprint(condition)
        val state = filterState(condition = condition)
        val afterFirstRequest =
            assertIs<P503AppState.TagMerchantFilter>(reducer.reduce(state, P503UiEvent.RequestTagMerchantFilterPage(replace = false)))
        val afterDuplicateRequest =
            assertIs<P503AppState.TagMerchantFilter>(reducer.reduce(afterFirstRequest, P503UiEvent.RequestTagMerchantFilterPage(replace = false)))
        assertEquals(afterFirstRequest.pageRequestToken + 1, afterDuplicateRequest.pageRequestToken)
        val page = successPage(entry("tx-1", Instant.parse("2026-03-01T00:00:00Z"), tagIds = listOf(tagA)))
        // The first read's landing carries its (now stale) token: dropped whole.
        val stale =
            assertIs<P503AppState.TagMerchantFilter>(
                reducer.reduce(afterDuplicateRequest, P503UiEvent.TagMerchantFilterPageLanded(fp, afterFirstRequest.pageRequestToken, page)),
            )
        assertTrue(stale.loadedEntries.isEmpty())
        assertTrue(stale.pageLoading)
        // The current read's landing applies exactly once.
        val current =
            assertIs<P503AppState.TagMerchantFilter>(
                reducer.reduce(stale, P503UiEvent.TagMerchantFilterPageLanded(fp, afterDuplicateRequest.pageRequestToken, page)),
            )
        assertEquals(1, current.loadedEntries.size)
        assertTrue(!current.pageLoading)
    }

    @Test
    fun aLoadMoreLandingSupersededByAReplaceReRequestNeverMergesIntoTheFreshPage() {
        // Vector ②: a load-more read still in flight when a catalog command's replace
        // re-request dispatches — the replace advances the token and clears the rows, and the
        // old read's landing must never merge into the fresh page.
        val condition = TagMerchantFilterCondition(tagIds = setOf(tagA))
        val fp = fingerprint(condition)
        val page1 = successPage(entry("tx-1", Instant.parse("2026-03-01T00:00:00Z"), tagIds = listOf(tagA)), hasMore = true)
        val withPage1 =
            assertIs<P503AppState.TagMerchantFilter>(
                reducer.reduce(
                    reducer.reduce(filterState(condition = condition), P503UiEvent.RequestTagMerchantFilterPage(replace = true)),
                    P503UiEvent.TagMerchantFilterPageLanded(fp, 1L, page1),
                ),
            )
        val loadMoreInFlight =
            assertIs<P503AppState.TagMerchantFilter>(reducer.reduce(withPage1, P503UiEvent.RequestTagMerchantFilterPage(replace = false)))
        val replaced =
            assertIs<P503AppState.TagMerchantFilter>(reducer.reduce(loadMoreInFlight, P503UiEvent.RequestTagMerchantFilterPage(replace = true)))
        assertTrue(replaced.loadedEntries.isEmpty())
        assertTrue(replaced.pageLoading)
        // The superseded load-more landing is dropped whole — it never appends its rows.
        val oldLanding =
            assertIs<P503AppState.TagMerchantFilter>(
                reducer.reduce(
                    replaced,
                    P503UiEvent.TagMerchantFilterPageLanded(
                        fp,
                        loadMoreInFlight.pageRequestToken,
                        successPage(entry("tx-2", Instant.parse("2026-03-02T00:00:00Z"), tagIds = listOf(tagA))),
                    ),
                ),
            )
        assertTrue(oldLanding.loadedEntries.isEmpty())
        assertTrue(oldLanding.pageLoading)
        // The replace read's landing rebuilds the page exactly once.
        val freshLanding =
            assertIs<P503AppState.TagMerchantFilter>(
                reducer.reduce(
                    oldLanding,
                    P503UiEvent.TagMerchantFilterPageLanded(
                        fp,
                        replaced.pageRequestToken,
                        successPage(entry("tx-3", Instant.parse("2026-03-03T00:00:00Z"), tagIds = listOf(tagA))),
                    ),
                ),
            )
        assertEquals(listOf("tx-3"), freshLanding.loadedEntries.map { it.row.transactionId.value })
        assertTrue(!freshLanding.pageLoading)
    }

    // ---- the accumulation's month gate ----

    @Test
    fun anOldMonthAccumulationLandingIsDroppedAndAMonthChangeClearsIt() {
        val condition = TagMerchantFilterCondition(merchantId = merchant)
        val state = filterState(condition = condition)
        val landed =
            assertIs<P503AppState.TagMerchantFilter>(
                reducer.reduce(state, P503UiEvent.MerchantAccumulationLanded(fingerprint(condition), 0L, march, successAccumulation())),
            )
        assertEquals(1, landed.accumulation?.let { (it as com.unifiedledger.application.MerchantMonthlyAccumulationResult.Success).transactionCount })
        // A month change clears the result; the OLD month's landing is dropped.
        val shifted = assertIs<P503AppState.TagMerchantFilter>(reducer.reduce(landed, P503UiEvent.SetFilterAccumulationMonth(YearMonth(2026, 4))))
        assertNull(shifted.accumulation)
        val stale =
            assertIs<P503AppState.TagMerchantFilter>(
                reducer.reduce(shifted, P503UiEvent.MerchantAccumulationLanded(fingerprint(condition), 0L, march, successAccumulation())),
            )
        assertNull(stale.accumulation)
        val fresh =
            assertIs<P503AppState.TagMerchantFilter>(
                reducer.reduce(shifted, P503UiEvent.MerchantAccumulationLanded(fingerprint(condition), 0L, YearMonth(2026, 4), successAccumulation())),
            )
        assertEquals(YearMonth(2026, 4), fresh.accumulationMonth)
    }

    // ---- pure presentation helpers ----

    @Test
    fun theFilterOptionsRuleIncludesDisabledAndExcludesTombstoned() {
        val disabled = catalogItem("tag-disabled", active = false, tombstoned = false)
        val tombstoned = catalogItem("tag-dead", active = false, tombstoned = true)
        val live = catalogItem("tag-live", active = true, tombstoned = false)
        val options = tagMerchantFilterTagOptions(listOf(disabled, tombstoned, live))
        // 停用标签仍可选入筛选器（spec 5.1）； tombstoned 不可命中任何筛选，只存在于审计面。
        assertEquals(setOf("tag-disabled", "tag-live"), options.map { it.id }.toSet())
    }

    @Test
    fun theConditionTextDistinguishesOrAndAnd() {
        val condition = TagMerchantFilterCondition(tagIds = setOf(tagA, tagB), tagMatch = TagMerchantTagMatchMode.All)
        assertTrue(tagMerchantFilterConditionText(condition, { tagA.value }, { null }).contains("且"))
        val or = condition.copy(tagMatch = TagMerchantTagMatchMode.Any)
        assertTrue(tagMerchantFilterConditionText(or, { tagA.value }, { null }).contains("或"))
        assertEquals("全部交易", tagMerchantFilterConditionText(TagMerchantFilterCondition(), { null }, { null }))
    }

    @Test
    fun failureTextsAreTypedAndNeverANoMatchesCopy() {
        TagMerchantFilterFailureCode.entries.forEach { code ->
            val text = tagMerchantFilterFailureText(code)
            assertTrue(text.isNotEmpty())
            assertTrue(!text.contains("没有命中"), "$code must not render as a no-matches copy")
        }
    }

    // ---- fixtures ----

    private fun successAccumulation(): com.unifiedledger.application.MerchantMonthlyAccumulationResult =
        com.unifiedledger.application.MerchantMonthlyAccumulationResult.Success(
            catalogItemVersion = 1L,
            netExpenseByCurrency = mapOf(cny to 1_000L),
            transactionCount = 1,
        )

    private fun catalog(): LedgerCatalog =
        when (
            val result =
                LedgerCatalog.create(
                    accounts =
                        listOf(
                            Account(AccountId("account-asset"), ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                            Account(AccountId("account-expense"), ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false),
                        ),
                    categories = emptyList(),
                )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> error("test catalog must be valid")
        }

    private fun catalogItem(
        id: String,
        active: Boolean,
        tombstoned: Boolean,
    ): com.unifiedledger.domain.CatalogItem =
        com.unifiedledger.domain.CatalogItem(
            kind = com.unifiedledger.domain.CatalogItemKind.TAG,
            id = id,
            ledgerId = ledgerId,
            name = id,
            active = active,
            tombstoned = tombstoned,
            revision = 1L,
        )

    // ------------------------------------------------------------------ P7-08 08.D (T8/T9)

    @Test
    fun aRestoreTriggeredReplaceReRequestDropsTheStalePreRestoreLanding() {
        // T8 (P708-A07 恢复腿; spec section 5.5 row 5): a restore commit arms the filter
        // re-request; the consume hop dispatches a REPLACE request which advances the request
        // token and clears the rows. The pre-restore read's late landing carries its stale token
        // and is dropped whole by the SAME frozen mechanism the two vectors above pin (token +
        // fingerprint — re-pinned here only as the restore-triggered instance); the fresh read's
        // landing rebuilds the page with the restored transaction.
        val condition = TagMerchantFilterCondition(tagIds = setOf(tagA))
        val fp = fingerprint(condition)
        val preRestorePage = successPage(entry("tx-restored", Instant.parse("2026-03-01T00:00:00Z"), tagIds = listOf(tagA)))
        // Before the restore the transaction was voided: the stale read returns an empty page
        // (its landing rides the pre-restore token).
        val withPreRestoreLanding =
            assertIs<P503AppState.TagMerchantFilter>(
                reducer.reduce(
                    reducer.reduce(filterState(condition = condition), P503UiEvent.RequestTagMerchantFilterPage(replace = true)),
                    P503UiEvent.TagMerchantFilterPageLanded(fp, 1L, successPage()),
                ),
            )
        // The restore arms + re-requests: a replace dispatch advances the token.
        val replaced =
            assertIs<P503AppState.TagMerchantFilter>(reducer.reduce(withPreRestoreLanding, P503UiEvent.RequestTagMerchantFilterPage(replace = true)))
        assertTrue(replaced.pageRequestToken > withPreRestoreLanding.pageRequestToken)
        // The stale (pre-restore) landing arrives late: dropped whole, never merged.
        val stale =
            assertIs<P503AppState.TagMerchantFilter>(
                reducer.reduce(
                    replaced,
                    P503UiEvent.TagMerchantFilterPageLanded(fp, withPreRestoreLanding.pageRequestToken, preRestorePage),
                ),
            )
        assertTrue(stale.loadedEntries.isEmpty())
        // The post-restore read's landing (fresh token) rebuilds the page with the restored row.
        val fresh =
            assertIs<P503AppState.TagMerchantFilter>(
                reducer.reduce(stale, P503UiEvent.TagMerchantFilterPageLanded(fp, replaced.pageRequestToken, preRestorePage)),
            )
        assertEquals(listOf("tx-restored"), fresh.loadedEntries.map { it.row.transactionId.value })
        assertTrue(!fresh.pageLoading)
    }

    @Test
    fun theRenameShowsTheNewCurrentNameOnTheConditionText() {
        // T9 (Q17 :273): the condition text resolves display names through the caller's resolver,
        // so a renamed item renders under its CURRENT name while the stable id (and thus the
        // matching annotation) is unchanged — the data-layer leg of the rename chain is pinned in
        // TagMerchantFilterReadTest; this pins the presentation half.
        val renamedResolver: (TagId) -> String? = { if (it == tagA) "标签甲（新）" else null }
        val condition = TagMerchantFilterCondition(tagIds = setOf(tagA), merchantId = merchant)
        val text = tagMerchantFilterConditionText(condition, renamedResolver, { "商家一" })
        assertTrue(text.contains("标签甲（新）"), "the condition text must show the renamed current name: $text")
        assertTrue(text.contains("商家一"), "the merchant condition shows the current name: $text")
        assertTrue(!text.contains("tag-a"), "the stable id must never leak into the condition text: $text")
    }
}
