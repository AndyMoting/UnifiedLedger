package com.unifiedledger.application

import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.MerchantId
import com.unifiedledger.domain.TagId
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.YearMonth
import kotlinx.datetime.plus

/*
 * P7-08 08.C (D-187; spec docs/specs/2026-09-30-p7-08-tag-merchant-design.md sections 5.1-5.4)
 * tag/merchant filter and merchant-accumulation read contracts.
 *
 * SEMANTICS FROZEN BY THE SPEC (section 5.1): no tag condition matches EVERY transaction; a
 * transaction carrying several selected tags counts ONCE under OR and once under AND; each
 * selected tag's group shows the FULL overlap amount and groups are never additive (the Kotlin
 * side folds each group independently); the merchant condition ANDs with the tag conditions; a
 * disabled (inactive) tag stays selectable in the filter and still matches its historical
 * annotations — only tombstoned items are excluded (tombstone requires zero references, so a
 * tombstoned item can never match anything, spec section 5.1).
 *
 * READ SHAPE FROZEN BY THE SPEC (section 5.2): a bounded keyset-paged read over a single read
 * transaction; the tag/merchant conditions enter as EXISTS semi-joins on the CURRENT annotation
 * so a multi-tag transaction is never row-multiplied; amounts are never DISTINCTed and never
 * SUMmed per group in SQL — the groups fold independently in Kotlin; the final display order is
 * the frozen [sortLedgerEntryRowsForDisplay] semantics applied in Kotlin.
 *
 * Section 5.3 discipline: a read failure, a bad catalog or a missing statistics projection is a
 * typed failure — never a zero rendered as "no matches"; a landing carrying an OLD condition
 * fingerprint (or an old lease generation, gated by the host) never overwrites the page.
 *
 * Open item 1 (the filter read port's exact names/signatures/failure codes) is ruled here as the
 * implementation batch's registered proposal; the names below are the frozen comparison surface.
 *
 * Open item 6 (merchant accumulation row source): this batch implements option (b) — a DEDICATED
 * named query over "transactions whose CURRENT annotation points at the merchant + month
 * statistics window + effective predicate", folded in Kotlin by the frozen
 * [MonthlyBuckets.aggregate]; no second kind table exists (the D-184 behavior-equivalence hard
 * constraint) and refunds count only through their OWN explicit annotation.
 */

/** OR/AND switch of the multi-tag condition (spec section 5.1: default OR, switchable AND). */
enum class TagMerchantTagMatchMode {
    /** Any selected tag matches (OR). The frozen default. */
    Any,

    /** Every selected tag must match (AND). */
    All,
}

/**
 * The filter condition draft (spec section 5.1). [tagIds] is a set (a duplicated selection is the
 * same condition); [merchantId] `null` = 不关联 (no merchant constraint). A condition with no
 * tags and no merchant matches every transaction.
 */
data class TagMerchantFilterCondition(
    val tagIds: Set<TagId> = emptySet(),
    val tagMatch: TagMerchantTagMatchMode = TagMerchantTagMatchMode.Any,
    val merchantId: MerchantId? = null,
)

/**
 * The canonical condition record: the ONLY equivalence basis of a filter request's fingerprint.
 * Tag ids are sorted (stable-id order, spec section 2.3), the match mode and merchant id follow.
 * The ledger id prefixes the record so fingerprints never cross ledgers.
 */
fun canonicalTagMerchantFilterConditionSnapshot(
    ledgerId: LedgerId,
    condition: TagMerchantFilterCondition,
): String {
    val tags = condition.tagIds.map { it.value }.distinct().sorted()
    return "{\"ledger\":${jcsString(ledgerId.value)},\"tags\":[" +
        tags.joinToString(separator = ",") { jcsString(it) } +
        "],\"match\":${jcsString(condition.tagMatch.name)},\"merchant\":" +
        (condition.merchantId?.let { jcsString(it.value) } ?: "null") +
        "}"
}

/**
 * The condition fingerprint a filter request carries and a landing is validated against (spec
 * section 5.3). Mirrors [tagMerchantInputFingerprint]: a derived integrity digest that is never
 * itself an identity — two equal conditions always produce equal fingerprints.
 */
fun tagMerchantFilterConditionFingerprint(
    ledgerId: LedgerId,
    condition: TagMerchantFilterCondition,
): String = "sha256:" + Sha256.digestHex(canonicalTagMerchantFilterConditionSnapshot(ledgerId, condition).encodeToByteArray())

/**
 * Stable filter/accumulation failure codes (spec section 5.3, open item 1). [code] is the frozen
 * literal consumers compare; enum constant names are only an internal spelling. A read failure
 * and a missing statistics projection are distinct so a caller can keep the fail-loud projection
 * discipline visible; a generation mismatch names the D-186 compare-in-transaction mechanism on
 * the dedicated `catalog_item_version` counter (open item 9, D-218 item 6).
 */
enum class TagMerchantFilterFailureCode(
    val code: String,
) {
    /** The read itself failed (database/driver). Never rendered as an empty result. */
    FILTER_UNAVAILABLE("TagMerchantFilterUnavailable"),

    /** A `statistics_at_epoch_nanos` projection is missing; fail-loud (spec section 6.4 item 5 lineage). */
    FILTER_MISSING_PROJECTION("TagMerchantFilterMissingProjection"),

    /** The read snapshot's `catalog_item_version` differs from the caller's expected generation. */
    FILTER_CATALOG_GENERATION_MISMATCH("TagMerchantCatalogGenerationMismatch"),

    /** The catalog authority is unreadable/inconsistent (posting account or catalog item unknown). */
    FILTER_BAD_CATALOG("TagMerchantFilterBadCatalog"),
    ;
}

/** Keyset cursor of the bounded filter read: the statistics projection instant + transaction id. */
data class TagMerchantFilterCursor(
    val statisticsAtNanos: Long,
    val transactionId: String,
)

/**
 * One matched transaction of a filter page: the CURRENT EFFECTIVE version's entry row (the same
 * [LedgerEntryRow] shape the monthly read folds) plus the CURRENT annotation's association. The
 * tag list is the current revision's tag set (deduplicated, stable-id order); a never-annotated
 * transaction (only reachable under an empty condition) carries an empty list and a null merchant.
 */
data class TagMerchantFilterEntry(
    val row: LedgerEntryRow,
    val tagIds: List<TagId>,
    val merchantId: MerchantId?,
)

/**
 * One bounded page of the filter read (spec section 5.2). [entries] is in the SQL keyset order
 * (`statistics_at` DESC, then transaction id DESC, postings by index) — a deterministic total
 * order the scale equivalence test pins; [nextCursor] is non-null exactly when [hasMore].
 */
data class TagMerchantFilterPage(
    val catalogItemVersion: Long,
    val entries: List<TagMerchantFilterEntry>,
    val hasMore: Boolean,
    val nextCursor: TagMerchantFilterCursor?,
    /**
     * The account catalog snapshot the read's single transaction produced, so the per-tag group
     * fold ([foldTagMerchantGroups]) dispatches account kinds without a second catalog read.
     */
    val catalog: LedgerCatalog,
)

sealed interface TagMerchantFilterReadResult {
    data class Success(
        val page: TagMerchantFilterPage,
    ) : TagMerchantFilterReadResult

    /** Fail-closed (spec section 5.3): never an empty page rendered as "no matches". */
    data class Failed(
        val failureCode: TagMerchantFilterFailureCode,
    ) : TagMerchantFilterReadResult
}

/**
 * The bounded filter read port (spec sections 5.2/5.3). Implementations MUST run the whole read —
 * the missing-projection probe, the `catalog_item_version` generation load and comparison against
 * [expectedCatalogItemVersion], and both query steps — inside ONE read transaction, so the page
 * and its generation come from one snapshot; every failure is the typed family above.
 */
interface TagMerchantFilterReadPort {
    fun readPage(
        ledgerId: LedgerId,
        condition: TagMerchantFilterCondition,
        expectedCatalogItemVersion: Long,
        pageSize: Int,
        cursor: TagMerchantFilterCursor?,
    ): TagMerchantFilterReadResult
}

sealed interface MerchantAccumulationReadResult {
    data class Success(
        val catalogItemVersion: Long,
        val catalog: LedgerCatalog,
        val rows: List<LedgerEntryRow>,
    ) : MerchantAccumulationReadResult

    /** Fail-closed (spec section 5.3): never zero rows rendered as "no spending". */
    data class Failed(
        val failureCode: TagMerchantFilterFailureCode,
    ) : MerchantAccumulationReadResult
}

/**
 * The merchant accumulation row-source port (spec section 5.4, open item 6 option (b)): the
 * bounded month-window rows of the transactions whose CURRENT annotation names [merchantId].
 * The effective predicate is the single `transaction_effective_state` definition point; a refund
 * counts only when the refund transaction's OWN annotation names the merchant. The `[start, end)`
 * bounds are calendar-month bounds of the frozen reporting zone; the adapter projects them to
 * epoch nanoseconds and fails loudly on a missing projection.
 */
interface MerchantAccumulationReadPort {
    fun readMerchantRows(
        ledgerId: LedgerId,
        merchantId: MerchantId,
        startInclusive: Instant,
        endExclusive: Instant,
        expectedCatalogItemVersion: Long,
    ): MerchantAccumulationReadResult
}

/**
 * The pure reference matcher of the frozen condition semantics (spec section 5.1): a NULL tag
 * condition matches every entry; OR is "any selected tag in the entry's current tag set"; AND is
 * "every selected tag present"; the merchant condition (when set) ANDs with the tag conditions
 * and matches the entry's current annotation merchant. The bounded SQL read implements the same
 * semantics with EXISTS semi-joins; this pure function is the equivalence reference the scale
 * test folds against and the single Kotlin statement of the OR/AND semantics.
 */
fun tagMerchantConditionMatches(
    condition: TagMerchantFilterCondition,
    entry: TagMerchantFilterEntry,
): Boolean {
    if (condition.tagIds.isNotEmpty()) {
        val entryTags = entry.tagIds.map(TagId::value).toSet()
        val selected = condition.tagIds.map(TagId::value).toSet()
        val tagsMatch =
            when (condition.tagMatch) {
                TagMerchantTagMatchMode.Any -> selected.any(entryTags::contains)
                TagMerchantTagMatchMode.All -> entryTags.containsAll(selected)
            }
        if (!tagsMatch) return false
    }
    return condition.merchantId == null || entry.merchantId == condition.merchantId
}

/**
 * The filter-page read use case: ONE bounded [TagMerchantFilterReadPort] read per call. The
 * port's typed failures pass through verbatim (fail-closed, spec section 5.3); the page stays in
 * the port's keyset order and the caller applies the frozen display order
 * ([sortTagMerchantFilterEntriesForDisplay]) to its ACCUMULATED pages, so the order never
 * depends on where a page boundary falls.
 */
class QueryTagMerchantFilterPage(
    private val readPort: TagMerchantFilterReadPort,
) {
    fun query(
        ledgerId: LedgerId,
        condition: TagMerchantFilterCondition,
        expectedCatalogItemVersion: Long,
        pageSize: Int,
        cursor: TagMerchantFilterCursor?,
    ): TagMerchantFilterReadResult =
        try {
            readPort.readPage(ledgerId, condition, expectedCatalogItemVersion, pageSize, cursor)
        } catch (failure: Exception) {
            TagMerchantFilterReadResult.Failed(TagMerchantFilterFailureCode.FILTER_UNAVAILABLE)
        }
}

/**
 * The frozen display order of a filter page: the [sortLedgerEntryRowsForDisplay] semantics
 * (`statistics_at` DESC, then `occurred_at` DESC, then transaction id ASC) applied to each
 * entry's row. Pure and stable; postings keep their stored index order.
 */
fun sortTagMerchantFilterEntriesForDisplay(entries: List<TagMerchantFilterEntry>): List<TagMerchantFilterEntry> =
    entries.sortedWith(
        compareByDescending<TagMerchantFilterEntry> { it.row.statisticsAt }
            .thenByDescending { it.row.occurredAt }
            .thenBy { it.row.transactionId.value },
    )

/**
 * Per-tag group folding (spec section 5.1): each selected tag's group is the matched entries whose
 * CURRENT annotation carries that tag, folded INDEPENDENTLY by the frozen [MonthlyBuckets.aggregate]
 * (the D-184 behavior-equivalence hard constraint — no second kind table). Groups overlap by
 * design; the map values are per-currency ordinary net expense minor units and MUST NOT be added
 * across groups. A group whose tag matches nothing is absent from the map.
 *
 * Fail-closed like every fold: a posting account absent from [catalog] throws
 * [IllegalStateException] and a checked overflow throws [ArithmeticException] (both via
 * [MonthlyBuckets.aggregate]). The two callers handle that differently — the host's tag/merchant
 * read adapter (ledger-data) catches and maps the exceptions to the typed bad-catalog failure,
 * while the filter screen folds its already-landed, adapter-validated pages inside
 * `remember {}` and fails loud instead (the shell's unhandled-event precedent): a catalog
 * snapshot that lost a posting account is a broken invariant, not a renderable empty result.
 */
fun foldTagMerchantGroups(
    entries: List<TagMerchantFilterEntry>,
    selectedTagIds: Set<TagId>,
    ledgerId: LedgerId,
    catalog: LedgerCatalog,
): Map<TagId, Map<CurrencyUnit, Long>> {
    if (selectedTagIds.isEmpty()) return emptyMap()
    val byTag = selectedTagIds.associateWith { tag -> entries.filter { entry -> entry.tagIds.any { it.value == tag.value } } }
    return byTag
        .mapValues { (_, groupEntries) ->
            if (groupEntries.isEmpty()) {
                emptyMap()
            } else {
                val months = groupEntries.map { MonthlyBuckets.bucketKey(it.row.statisticsAt) }.distinct()
                MonthlyBuckets
                    .aggregate(groupEntries.map(TagMerchantFilterEntry::row), ledgerId, catalog, months)
                    .values
                    .flatMap { month -> month.currencies }
                    .fold(emptyMap<CurrencyUnit, Long>()) { acc, activity ->
                        val merged = checkedAddMinor(acc[activity.currency] ?: 0L, activity.netExpenseMinorUnits)
                            ?: throw ArithmeticException("tag group net expense overflow for ${activity.currency.code}")
                        acc + (activity.currency to merged)
                    }
            }
        }.filterValues { group -> group.isNotEmpty() }
}

/** Exact checked minor-unit addition (the [MonthlyBuckets] discipline); null on overflow. */
private fun checkedAddMinor(
    left: Long,
    right: Long,
): Long? {
    if (right > 0 && left > Long.MAX_VALUE - right) return null
    if (right < 0 && left < Long.MIN_VALUE - right) return null
    return left + right
}

/** The matched-set transaction count of one tag group (independent per group; never additive). */
fun tagMerchantGroupTransactionCount(
    entries: List<TagMerchantFilterEntry>,
    tagId: TagId,
): Int = entries.count { entry -> tagId.value in entry.tagIds.map(TagId::value) }

sealed interface MerchantMonthlyAccumulationResult {
    data class Success(
        val catalogItemVersion: Long,
        /** Per-currency ordinary net expense of the merchant's month (exact minor units). */
        val netExpenseByCurrency: Map<CurrencyUnit, Long>,
        /** The month's matched-transaction count (all kinds, the aggregate's honest count). */
        val transactionCount: Int,
    ) : MerchantMonthlyAccumulationResult

    /** Fail-closed (spec section 5.3): never a zero rendered as "no spending this month". */
    data class Failed(
        val failureCode: TagMerchantFilterFailureCode,
    ) : MerchantMonthlyAccumulationResult
}

/**
 * The merchant monthly ordinary net expense use case (spec section 5.4, open item 6 option (b)).
 * ONE bounded [MerchantAccumulationReadPort] read for the month, folded ONCE by
 * [MonthlyBuckets.aggregate]; ordinary income never appears (net expense only) and the excluded
 * special kinds contribute zero — the UI must therefore declare 不含特殊消费.
 *
 * Fail-closed (spec section 5.3): the port's typed failures map verbatim; a posting account
 * missing from the catalog or a checked overflow is the typed bad-catalog failure — never a
 * fabricated zero; a month with an annotated merchant but no matching transactions is a genuine
 * zero (an empty result, not a failure).
 */
class QueryMerchantMonthlyAccumulation(
    private val readPort: MerchantAccumulationReadPort,
) {
    fun query(
        ledgerId: LedgerId,
        merchantId: MerchantId,
        month: YearMonth,
        expectedCatalogItemVersion: Long,
    ): MerchantMonthlyAccumulationResult {
        val start = MonthlyBuckets.monthStart(month)
        val end = MonthlyBuckets.monthStart(month.plus(1, DateTimeUnit.MONTH))
        val read =
            try {
                readPort.readMerchantRows(ledgerId, merchantId, start, end, expectedCatalogItemVersion)
            } catch (failure: Exception) {
                return MerchantMonthlyAccumulationResult.Failed(TagMerchantFilterFailureCode.FILTER_UNAVAILABLE)
            }
        val rows =
            when (read) {
                is MerchantAccumulationReadResult.Success -> read.rows
                is MerchantAccumulationReadResult.Failed -> return MerchantMonthlyAccumulationResult.Failed(read.failureCode)
            }
        return try {
            val activity = MonthlyBuckets.aggregate(rows, ledgerId, read.catalog, listOf(month)).getValue(month)
            MerchantMonthlyAccumulationResult.Success(
                catalogItemVersion = read.catalogItemVersion,
                netExpenseByCurrency = activity.currencies.associate { it.currency to it.netExpenseMinorUnits },
                // The per-currency slices repeat the month's whole transaction count (the
                // aggregate counts every transaction exactly once per month).
                transactionCount = activity.currencies.firstOrNull()?.transactionCount ?: 0,
            )
        } catch (failure: IllegalStateException) {
            MerchantMonthlyAccumulationResult.Failed(TagMerchantFilterFailureCode.FILTER_BAD_CATALOG)
        } catch (failure: ArithmeticException) {
            MerchantMonthlyAccumulationResult.Failed(TagMerchantFilterFailureCode.FILTER_BAD_CATALOG)
        }
    }
}
