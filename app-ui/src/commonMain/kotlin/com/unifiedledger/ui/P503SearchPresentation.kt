package com.unifiedledger.ui

import com.unifiedledger.application.SearchCondition
import com.unifiedledger.application.SearchFailureCode
import com.unifiedledger.application.SearchTimeWindow
import com.unifiedledger.application.TagMerchantTagMatchMode
import com.unifiedledger.domain.CatalogItem
import kotlin.time.Instant

// P8-02 i2a (spec `2026-11-11-p8-02-search-filter-design.md` sections 2.1/2.4/2.5; D7-1..D7-7)
// pure presentation decisions for the search result surface. The composable stays a thin renderer;
// every load-bearing decision — which face the body shows, whether the draft may be submitted, how
// the draft becomes the port's condition group, and how each typed failure is worded — is a pure
// function here, so it is assertable in app-ui's JVM tests without a Compose UI-test harness
// (CONTRIBUTING: the repository forbids the Compose UI test framework in this module).
//
// THE CONDITION GROUP IS THE SHARED CONTRACT. The keyword, the window, the tags (OR/AND) and the
// merchant are assembled into [SearchCondition] — the ONE shape `QuerySearchPage` accepts — so this
// surface can never invent its own filter semantics: the P7-08 tag/merchant semantics the analysis
// page used to own are carried here by the SAME [TagMerchantTagMatchMode] and the SAME tag/merchant
// ids (V-10's "同一筛选语义可达"). The window is mandatory (spec section 2.1), so a draft whose
// custom range is not strictly increasing cannot be submitted at all.
//
// FAILURES ARE TYPED AND NEVER EMPTY (D-089 / R-Q06-4, V-7): every [SearchFailureCode] has its own
// copy and none of them contains the empty-result sentence, so a read failure can never be rendered
// as "没有找到".

// ------------------------------------------------------------------ frozen vocabulary

internal const val P8_SEARCH_TITLE = "搜索"

/** D7-6: the initial face before any request — the guide, never an empty result. */
internal const val P8_SEARCH_GUIDE_TEXT = "输入条件后开始搜索"

internal const val P8_SEARCH_SUBMIT_LABEL = "搜索"
internal const val P8_SEARCH_KEYWORD_LABEL = "关键词（备注 / 商家名 / 分类名）"
internal const val P8_SEARCH_WINDOW_HEADER = "时间窗"
internal const val P8_SEARCH_TAG_HEADER = "标签（可多选）"
internal const val P8_SEARCH_MERCHANT_HEADER = "商家（可选）"
internal const val P8_SEARCH_NO_MERCHANT_LABEL = "不关联商家"
internal const val P8_SEARCH_CUSTOM_START_LABEL = "开始（含）"
internal const val P8_SEARCH_CUSTOM_END_LABEL = "结束（不含）"
internal const val P8_SEARCH_CUSTOM_INVALID_TEXT = "自定义起止无效：开始必须早于结束。"
internal const val P8_SEARCH_RESULTS_HEADER = "搜索结果"
internal const val P8_SEARCH_LOADING_TEXT = "正在搜索…"
internal const val P8_SEARCH_TAG_MATCH_ANY_LABEL = "任一标签（或）"
internal const val P8_SEARCH_TAG_MATCH_ALL_LABEL = "全部标签（与）"
internal const val P8_SEARCH_TAGS_LOADING_TEXT = "标签目录载入中…"
internal const val P8_SEARCH_TAGS_EMPTY_TEXT = "当前没有可筛选的标签。"
internal const val P8_SEARCH_MERCHANTS_LOADING_TEXT = "商家目录载入中…"

/** The genuine empty result — reached only from a read that SUCCEEDED with zero rows. */
internal const val P8_SEARCH_EMPTY_TEXT = "没有找到匹配的交易。"

/**
 * V-12 / spec section 2.4: the two canvas dimensions this slice does NOT implement. They stay
 * VISIBLE and permanently disabled — never silently hidden — because the canvas draws them and the
 * spec forbids dropping them without a word.
 */
internal val P8_SEARCH_DISABLED_CHIPS: List<String> = listOf("账户", "金额区间")

/** V-12: the disabled chips' state. A named constant so the test pins "visible but not clickable". */
internal const val P8_SEARCH_DISABLED_CHIP_ENABLED: Boolean = false

// ------------------------------------------------------------------ the mandatory time window

/**
 * The three window choices of spec section 2.1 (本月 / 近 90 天 / 自定义起止). The enum's declaration
 * order IS the render order, so the condition area cannot reorder them.
 */
enum class SearchWindowChoice {
    CURRENT_MONTH,
    LAST_NINETY_DAYS,
    CUSTOM,
}

/** The frozen chip label of one window choice. */
internal fun searchWindowLabel(choice: SearchWindowChoice): String =
    when (choice) {
        SearchWindowChoice.CURRENT_MONTH -> "本月"
        SearchWindowChoice.LAST_NINETY_DAYS -> "近 90 天"
        SearchWindowChoice.CUSTOM -> "自定义起止"
    }

/**
 * The window the draft resolves to, or `null` when a CUSTOM range's two drafts are not a pair of
 * strictly increasing instants. `null` disables submit, so an invalid range never reaches the use
 * case (whose own typed answer for one would be [SearchFailureCode.SEARCH_INVALID_WINDOW]).
 */
internal fun searchWindowOf(
    choice: SearchWindowChoice,
    customStartText: String,
    customEndText: String,
): SearchTimeWindow? =
    when (choice) {
        SearchWindowChoice.CURRENT_MONTH -> SearchTimeWindow.CurrentMonth
        SearchWindowChoice.LAST_NINETY_DAYS -> SearchTimeWindow.LastNinetyDays
        SearchWindowChoice.CUSTOM -> {
            val start = parseSearchInstant(customStartText)
            val end = parseSearchInstant(customEndText)
            if (start == null || end == null || start >= end) null else SearchTimeWindow.Custom(start, end)
        }
    }

/** The ISO-8601 instant the user typed, or `null` when the text is not one. */
private fun parseSearchInstant(text: String): Instant? = runCatching { Instant.parse(text.trim()) }.getOrNull()

/**
 * Whether the surface may submit the current draft: never while a page read is in flight
 * (一次一页) and never with an unresolvable window. An empty keyword IS submittable — it degrades to
 * the window + tag/merchant filter (D7-6 / spec section 2.2 item 4).
 */
internal fun searchSubmitEnabled(state: P503AppState.Search): Boolean =
    !state.pageLoading &&
        searchWindowOf(state.window, state.customStartText, state.customEndText) != null

/**
 * The four-item condition group this surface submits (spec section 2.1). `null` only when the draft
 * carries no resolvable window — unreachable through the screen because [searchSubmitEnabled] gates
 * the action on the same decision.
 *
 * The tag/merchant legs are the SHARED contract fields, so the semantics the retired analysis-page
 * filter owned are reached here unchanged (V-10): the same tag ids, the same OR/AND mode and the
 * same optional merchant AND together with the keyword.
 */
internal fun P503AppState.Search.toSearchCondition(): SearchCondition? {
    val resolvedWindow = searchWindowOf(window, customStartText, customEndText) ?: return null
    return SearchCondition(
        keyword = keyword,
        window = resolvedWindow,
        tagIds = tagIds,
        tagMatch = tagMatch,
        merchantFilterId = merchantId,
    )
}

// ------------------------------------------------------------------ the body face

/** Which body the result surface renders. */
internal enum class SearchFace {
    /** D7-6: nothing requested yet — the guide, never an empty-result claim. */
    GUIDE,

    /** The first page is in flight and no row has landed yet. */
    LOADING,

    /** The read failed with nothing loaded: the typed failure face with its own retry (V-7). */
    UNAVAILABLE,

    /** A successful read with zero rows — the ONLY face that may claim an empty result. */
    EMPTY,

    /** The rows (and, on a more-page failure, the rows plus the banner above them). */
    LIST,
}

/**
 * The body decision. Rows outrank every banner (a failed more-page read keeps the loaded rows and
 * renders its banner above them); without rows the order is loading, then the typed failure, then
 * the no-read-issued guide, then the genuine empty. [P503AppState.Search.readIssued] is checked
 * first: a page reset clears this flag, so a condition edited after a landed read is unread again
 * and must show the guide, never the empty-result claim.
 */
internal fun searchFace(state: P503AppState.Search): SearchFace =
    when {
        state.entries.isNotEmpty() -> SearchFace.LIST
        state.pageLoading -> SearchFace.LOADING
        state.pageFailure != null -> SearchFace.UNAVAILABLE
        !state.readIssued -> SearchFace.GUIDE
        else -> SearchFace.EMPTY
    }

/** The paged footer of the result list (the shared [P8FlowFooter] vocabulary). */
internal fun searchFooterState(state: P503AppState.Search): FlowFooterState =
    when {
        state.pageLoading -> FlowFooterState.LOADING
        state.pageFailure != null -> FlowFooterState.RETRY
        state.hasMore -> FlowFooterState.FETCH_MORE
        else -> FlowFooterState.END
    }

// ------------------------------------------------------------------ typed copy

/**
 * The typed failure copy (V-7): each code names what happened, and NO branch carries the
 * empty-result sentence — a read failure is never rendered as "没有找到" (D-089 / R-Q06-4).
 */
internal fun searchFailureText(code: SearchFailureCode): String =
    when (code) {
        SearchFailureCode.SEARCH_UNAVAILABLE -> "搜索读取失败，请重试"
        SearchFailureCode.SEARCH_MISSING_PROJECTION -> "搜索读取失败：统计时间投影缺失，请刷新后重试"
        SearchFailureCode.SEARCH_BAD_CATALOG -> "目录读取失败，请刷新目录后重试"
        SearchFailureCode.SEARCH_INVALID_WINDOW -> "时间窗无效，请调整起止后重试"
    }

// ------------------------------------------------------------------ the condition area's option rules

/**
 * The condition area's tag-option rule: DISABLED items INCLUDED (a disabled tag still matches its
 * historical annotations), tombstoned EXCLUDED (zero references at delete time). This is the rule
 * the retired filter surface applied (P7-08 spec section 5.1), reached here unchanged — V-10's
 * "same filter semantics reachable".
 */
internal fun searchTagConditionOptions(items: List<CatalogItem>): List<CatalogItem> = items.filter { !it.tombstoned }

/** The merchant-option rule — same disabled-included / tombstoned-excluded shape. */
internal fun searchMerchantConditionOptions(items: List<CatalogItem>): List<CatalogItem> = items.filter { !it.tombstoned }

/** The OR/AND label of one tag match mode (the frozen wording). */
internal fun searchTagMatchLabel(mode: TagMerchantTagMatchMode): String =
    when (mode) {
        TagMerchantTagMatchMode.Any -> P8_SEARCH_TAG_MATCH_ANY_LABEL
        TagMerchantTagMatchMode.All -> P8_SEARCH_TAG_MATCH_ALL_LABEL
    }

/**
 * V-11's carrier without a Compose harness: every frozen condition-area label. The surface
 * offers no calendar entry and no month grid (spec section 2.5 — the calendar belongs to P8-06), so
 * this list holds no calendar vocabulary; adding an affordance changes the list, and the test pins
 * the whole set so nothing can be added silently.
 */
internal fun p8SearchAffordanceLabels(): List<String> =
    listOf(
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
    ) + SearchWindowChoice.entries.map(::searchWindowLabel) + P8_SEARCH_DISABLED_CHIPS
