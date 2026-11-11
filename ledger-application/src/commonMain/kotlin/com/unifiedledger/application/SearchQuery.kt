package com.unifiedledger.application

import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.MerchantId
import com.unifiedledger.domain.TagId
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/*
 * P8-02 i1 (D-310; spec docs/specs/2026-11-11-p8-02-search-filter-design.md sections 2.1-2.3, 4)
 * search read contracts: the condition group, the resolved-by-the-application-layer keyword, the
 * bounded page port and its ONE use case.
 *
 * SEMANTICS FROZEN BY THE SPEC (section 2.1): the condition group is exactly four items — keyword,
 * time window, tags, merchants (a category rides the keyword, section 2.2) — and they AND together.
 * The time window is MANDATORY: there is no windowless search, because `note` and the category arm
 * are unindexed and the window is the only thing that bounds how long the read holds the single
 * production connection. Inside the tag item the P7-08 fenced OR/AND semantics are reused unchanged
 * ([TagMerchantTagMatchMode], [TagMerchantFilterCursor]), never re-invented.
 *
 * WHERE EACH CONDITION IS RESOLVED (one definition point per item):
 *
 *  - the keyword is resolved HERE, against the two CURRENT name tables the existing catalog reads
 *    already load (F5; section 2.2 "两张目录表每账本只有几十到几百行"): category names through
 *    [CatalogAuthorityReader], merchant names through [TagMerchantCatalogReader]. No new name
 *    query is added, an empty id set turns its arm off, and a renamed item answers only to its
 *    CURRENT name (the registered consequence: 改名前的旧名不命中);
 *  - the window is resolved HERE into a half-open `[start, end)` instant pair from the frozen
 *    reporting zone ([MonthlyBuckets.REPORT_TIME_ZONE]) and the injected clock, so the SQL side
 *    only ever compares epoch nanoseconds;
 *  - the two name arms carry one boolean switch each, because an empty id set must never reach SQL
 *    as an empty list (the tag filter's one-element-sentinel precedent).
 *
 * READ SHAPE FROZEN BY THE SPEC (section 2.3): one bounded keyset page over ONE read transaction,
 * the page unit being a TRANSACTION (keyset `(statistics_at DESC, transaction_id DESC)`, page size
 * 50, `NULL` cursor = first page), plus the independent "共 N 笔" count of the same condition
 * group. A read failure — of the page, of the count, of a catalog name load, or of a connection —
 * is a TYPED failure and never an empty page rendered as "没有找到" (D-089 / R-Q06-4).
 *
 * Read-only: nothing here writes, and no search condition is persisted.
 */

/** The spec's default window length in days (section 2.1: 默认近 90 天). */
const val P802_SEARCH_DEFAULT_WINDOW_DAYS: Int = 90

/** The frozen page size of the search read (section 2.3: 50, not the home flow's 200). */
const val P802_SEARCH_PAGE_SIZE: Int = 50

/**
 * The mandatory time window of one search (spec section 2.1: 默认近 90 天, 本月, 自定义起止). The
 * resolved half-open bounds always come from [MonthlyBuckets.REPORT_TIME_ZONE], never from a
 * caller-supplied zone.
 */
sealed interface SearchTimeWindow {
    /** The trailing [P802_SEARCH_DEFAULT_WINDOW_DAYS] days ending at the clock's current instant. */
    data object LastNinetyDays : SearchTimeWindow

    /** The whole calendar month of the clock's current instant in the frozen zone. */
    data object CurrentMonth : SearchTimeWindow

    /**
     * A caller-supplied `[startInclusive, endExclusive)` range. A range that is not strictly
     * increasing is [SearchFailureCode.SEARCH_INVALID_WINDOW], never an empty page.
     */
    data class Custom(
        val startInclusive: Instant,
        val endExclusive: Instant,
    ) : SearchTimeWindow
}

/**
 * The four-item search condition group (spec section 2.1) as the UI submits it: the keyword is RAW
 * user input here and is escaped/resolved by [QuerySearchPage]. [tagIds] is a set (a duplicated
 * selection is one condition); [merchantFilterId] `null` means no merchant filter condition.
 */
data class SearchCondition(
    val keyword: String = "",
    val window: SearchTimeWindow = SearchTimeWindow.LastNinetyDays,
    val tagIds: Set<TagId> = emptySet(),
    val tagMatch: TagMerchantTagMatchMode = TagMerchantTagMatchMode.Any,
    val merchantFilterId: MerchantId? = null,
)

/**
 * The condition group after application-layer resolution: the ONLY shape the read port accepts, so
 * no adapter can invent its own keyword or window semantics.
 *
 * [keyword] is the LIKE-escaped argument (see [escapeSearchLikeKeyword]); empty means "no keyword",
 * which also disables the note arm. [keywordCategoryIds] and [keywordMerchantIds] are the name-arm
 * id sets; the matching arm is on exactly when the keyword is non-empty AND its set is non-empty.
 */
data class ResolvedSearchCondition(
    val keyword: String,
    val windowStartInclusive: Instant,
    val windowEndExclusive: Instant,
    val keywordCategoryIds: Set<CategoryId> = emptySet(),
    val keywordMerchantIds: Set<MerchantId> = emptySet(),
    val tagIds: Set<TagId> = emptySet(),
    val tagMatch: TagMerchantTagMatchMode = TagMerchantTagMatchMode.Any,
    val merchantFilterId: MerchantId? = null,
) {
    /** The category name arm is active (spec section 2.2 item 4: an empty keyword turns all off). */
    val categoryArm: Boolean get() = keyword.isNotEmpty() && keywordCategoryIds.isNotEmpty()

    /** The merchant name arm is active, on the same rule as [categoryArm]. */
    val merchantArm: Boolean get() = keyword.isNotEmpty() && keywordMerchantIds.isNotEmpty()
}

/**
 * Stable search failure codes (D-089 / R-Q06-4). [code] is the frozen literal a consumer compares;
 * the enum constant name is only an internal spelling. A read failure and a missing statistics
 * projection stay distinct so the fail-loud projection discipline stays visible.
 */
enum class SearchFailureCode(
    val code: String,
) {
    /** The read itself failed (database, driver, connection). Never rendered as an empty result. */
    SEARCH_UNAVAILABLE("SearchUnavailable"),

    /** A `statistics_at_epoch_nanos` projection is missing; fail-loud (the projection discipline). */
    SEARCH_MISSING_PROJECTION("SearchMissingProjection"),

    /** A catalog authority needed to resolve the keyword is unreadable. */
    SEARCH_BAD_CATALOG("SearchBadCatalog"),

    /** A custom window that is not strictly increasing; the caller's input, refused typed. */
    SEARCH_INVALID_WINDOW("SearchInvalidWindow"),
}

/**
 * One bounded page of the search read plus the "共 N 笔" count of the same condition group, read
 * from ONE transaction snapshot. [entries] is in the SQL keyset order (`statistics_at` DESC, then
 * transaction id DESC, postings by index); [nextCursor] is non-null exactly when [hasMore].
 */
data class SearchPage(
    val entries: List<LedgerEntryRow>,
    val hasMore: Boolean,
    val nextCursor: TagMerchantFilterCursor?,
    /** The count of the whole condition group, not of this page. */
    val totalCount: Long,
)

sealed interface SearchReadResult {
    data class Success(
        val page: SearchPage,
    ) : SearchReadResult

    /** Fail-closed (D-089 / R-Q06-4): never an empty page rendered as "没有找到". */
    data class Failed(
        val failureCode: SearchFailureCode,
    ) : SearchReadResult
}

/**
 * The bounded search read port (spec sections 2.3/4). An implementation MUST run the whole read —
 * the fail-loud missing-projection probe, the page statement and the count statement — inside ONE
 * read transaction, so the page and its count come from one snapshot, and MUST map every failure to
 * [SearchReadResult.Failed]. The [pageSize] is the caller's (the frozen default is
 * [P802_SEARCH_PAGE_SIZE]); the [cursor] is `null` for the first page.
 */
interface SearchReadPort {
    fun readPage(
        ledgerId: LedgerId,
        condition: ResolvedSearchCondition,
        pageSize: Int,
        cursor: TagMerchantFilterCursor?,
    ): SearchReadResult
}

/**
 * Backslash-escapes the LIKE metacharacters of a user keyword (spec section 2.2 / F3): `\`, `%` and
 * `_` become literal characters under the statement's `ESCAPE '\'`, so searching for "50%" matches
 * the text "50%" and not the prefix "50". The escape count is bounded by the keyword length, and
 * every other character — CJK included — passes through untouched.
 */
fun escapeSearchLikeKeyword(raw: String): String =
    raw
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

/**
 * The half-open instant bounds of [window] (spec section 2.1), or `null` for a custom range that is
 * not strictly increasing — the caller's typed [SearchFailureCode.SEARCH_INVALID_WINDOW]. Month
 * bounds come from [MonthlyBuckets], whose zone is the frozen reporting zone.
 */
fun resolveSearchTimeWindow(
    window: SearchTimeWindow,
    clock: LedgerClock,
): Pair<Instant, Instant>? =
    when (window) {
        SearchTimeWindow.LastNinetyDays -> {
            val end = clock.now()
            (end - P802_SEARCH_DEFAULT_WINDOW_DAYS.days) to end
        }
        SearchTimeWindow.CurrentMonth -> {
            val month = MonthlyBuckets.currentMonth(clock)
            MonthlyBuckets.monthStart(month) to MonthlyBuckets.monthEndExclusive(month)
        }
        is SearchTimeWindow.Custom ->
            if (window.startInclusive < window.endExclusive) {
                window.startInclusive to window.endExclusive
            } else {
                null
            }
    }

/**
 * The search page use case: ONE [SearchReadPort] read per call.
 *
 * The keyword is resolved against the two CURRENT-name catalog reads before the port is called, and
 * the window is resolved from [LedgerClock]; every failure — catalog, window, port — is a typed
 * [SearchReadResult.Failed] (fail-closed, spec section 2.3). A keyword whose name arms resolve to
 * nothing still reads: the keyword's note arm stays live, and a keyword that matches nothing
 * anywhere is a genuine empty page, not a failure.
 */
class QuerySearchPage(
    private val readPort: SearchReadPort,
    private val catalogReader: CatalogAuthorityReader,
    private val tagMerchantCatalogReader: TagMerchantCatalogReader,
    private val clock: LedgerClock,
) {
    fun query(
        ledgerId: LedgerId,
        condition: SearchCondition,
        pageSize: Int = P802_SEARCH_PAGE_SIZE,
        cursor: TagMerchantFilterCursor? = null,
    ): SearchReadResult {
        return try {
            val bounds =
                resolveSearchTimeWindow(condition.window, clock)
                    ?: return SearchReadResult.Failed(SearchFailureCode.SEARCH_INVALID_WINDOW)
            val keyword = condition.keyword
            val categoryIds =
                if (keyword.isEmpty()) {
                    emptySet()
                } else {
                    val catalog =
                        catalogReader.load(ledgerId)
                            ?: return SearchReadResult.Failed(SearchFailureCode.SEARCH_BAD_CATALOG)
                    catalog.catalog.categories
                        .filter { category -> category.name.contains(keyword, ignoreCase = true) }
                        .map { category -> category.id }
                        .toSet()
                }
            val merchantIds =
                if (keyword.isEmpty()) {
                    emptySet()
                } else {
                    val authority =
                        tagMerchantCatalogReader.load(ledgerId)
                            ?: return SearchReadResult.Failed(SearchFailureCode.SEARCH_BAD_CATALOG)
                    authority.merchants
                        .filter { merchant -> merchant.name.contains(keyword, ignoreCase = true) }
                        .map { merchant -> MerchantId(merchant.id) }
                        .toSet()
                }
            readPort.readPage(
                ledgerId = ledgerId,
                condition =
                    ResolvedSearchCondition(
                        keyword = escapeSearchLikeKeyword(keyword),
                        windowStartInclusive = bounds.first,
                        windowEndExclusive = bounds.second,
                        keywordCategoryIds = categoryIds,
                        keywordMerchantIds = merchantIds,
                        tagIds = condition.tagIds,
                        tagMatch = condition.tagMatch,
                        merchantFilterId = condition.merchantFilterId,
                    ),
                pageSize = pageSize,
                cursor = cursor,
            )
        } catch (failure: Exception) {
            SearchReadResult.Failed(SearchFailureCode.SEARCH_UNAVAILABLE)
        }
    }
}
