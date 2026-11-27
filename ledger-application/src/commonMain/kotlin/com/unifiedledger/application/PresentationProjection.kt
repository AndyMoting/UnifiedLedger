/**
 * Android presentation foundation (D-369 A): pure display projections over the existing read
 * models. Nothing here reads a database, changes a schema, or writes a ledger fact; every function
 * is a deterministic fold a JVM test can assert without a Compose harness.
 *
 * The contracts frozen here are the ones later presentation batches consume:
 * - a [TransactionKind] always has a Chinese business name and the `when` is exhaustive;
 * - a business [Instant] is rendered in the one reporting zone ([MonthlyBuckets.REPORT_TIME_ZONE])
 *   and the occurred/statistics roles never substitute for each other;
 * - an amount is exact minor units plus its currency code, with `Long.MIN_VALUE` and precision
 *   0..18 rendered without converting through a floating-point type;
 * - category presentation resolves only through the catalog `posting_account_id` association;
 * - an absent value names its own state instead of printing a zero.
 */
package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.TransactionKind
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

// ---------------------------------------------------------------------------------------------
// Business names
// ---------------------------------------------------------------------------------------------

/**
 * The Chinese business name of every [TransactionKind]. The `when` is exhaustive on purpose: a new
 * kind must choose its user-facing name here instead of falling through to a raw enum constant.
 */
fun transactionKindBusinessName(kind: TransactionKind): String =
    when (kind) {
        TransactionKind.OPENING_BALANCE -> "期初余额"
        TransactionKind.EXPENSE -> "支出"
        TransactionKind.INCOME -> "收入"
        TransactionKind.ACCOUNT_TRANSFER -> "转账"
        TransactionKind.CREDIT_REPAYMENT -> "信用还款"
        TransactionKind.REFUND_RECEIPT -> "退款到账"
        TransactionKind.BALANCE_ADJUSTMENT -> "余额调整"
        TransactionKind.BALANCE_ADJUSTMENT_REVERSAL -> "余额调整冲回"
        TransactionKind.STORED_VALUE_RECHARGE -> "储值充值"
        TransactionKind.STORED_VALUE_SPEND -> "储值消费"
        TransactionKind.STORED_VALUE_EXPIRY_LOSS -> "储值到期损失"
        TransactionKind.STORED_VALUE_PRE_ACTIVATION_BALANCE_ADJUSTMENT -> "储值接入前余额调整"
        TransactionKind.LEND -> "借出"
        TransactionKind.COLLECT -> "收回"
        TransactionKind.PREPAID_PURCHASE -> "预付购买"
        TransactionKind.PREPAID_RECOGNITION -> "预付确认"
    }

// ---------------------------------------------------------------------------------------------
// Time
// ---------------------------------------------------------------------------------------------

/** Which persisted business instant a display value came from. The two never substitute. */
enum class PresentationTimeRole(
    val label: String,
) {
    OCCURRED("发生时间"),
    STATISTICS("统计时间"),
}

/**
 * One business instant bound to its role. Every conversion uses the frozen reporting zone
 * ([MonthlyBuckets.REPORT_TIME_ZONE]); there is no per-call zone parameter, so a screen cannot
 * quietly pick a different day boundary.
 */
data class PresentationTime(
    val instant: Instant,
    val role: PresentationTimeRole,
) {
    fun localDateTime(): LocalDateTime = instant.toLocalDateTime(MonthlyBuckets.REPORT_TIME_ZONE)

    fun localDate(): LocalDate = localDateTime().date

    /** The local wall-clock text (`2026-11-27T10:47`), never a bare UTC instant. */
    fun text(): String {
        val local = localDateTime()
        val month = local.monthNumber.toString().padStart(2, '0')
        val day = local.dayOfMonth.toString().padStart(2, '0')
        val hour = local.hour.toString().padStart(2, '0')
        val minute = local.minute.toString().padStart(2, '0')
        return "${local.year}-$month-${day}T$hour:$minute"
    }
}

fun occurredPresentationTime(instant: Instant): PresentationTime = PresentationTime(instant, PresentationTimeRole.OCCURRED)

fun statisticsPresentationTime(instant: Instant): PresentationTime = PresentationTime(instant, PresentationTimeRole.STATISTICS)

/**
 * Both persisted instants of a row, each carrying its own role. A consumer that wants "the time"
 * must name the role, so an occurred/statistics swap is a compile-time choice rather than a
 * formatting accident.
 */
data class PresentationTimes(
    val occurred: PresentationTime,
    val statistics: PresentationTime,
) {
    init {
        require(occurred.role == PresentationTimeRole.OCCURRED) { "occurred role must be OCCURRED" }
        require(statistics.role == PresentationTimeRole.STATISTICS) { "statistics role must be STATISTICS" }
    }
}

fun presentationTimes(
    occurredAt: Instant,
    statisticsAt: Instant,
): PresentationTimes = PresentationTimes(occurredPresentationTime(occurredAt), statisticsPresentationTime(statisticsAt))

// ---------------------------------------------------------------------------------------------
// Amounts and summary labels
// ---------------------------------------------------------------------------------------------

/** Frozen summary vocabulary shared by the home card, the report and the TalkBack sentence. */
enum class PresentationSummaryLabel(
    val text: String,
) {
    NET_EXPENSE("净支出（含退款）"),
    ORDINARY_INCOME("普通收入"),
    BALANCE("结余"),
}

/**
 * The exact signed amount plus one space and the currency code (`-12.34 CNY`, `0.00 CNY`).
 *
 * `Long.MIN_VALUE` is rendered from its own decimal text, because negating it would overflow; the
 * sign is kept and no value is clamped to `Long.MAX_VALUE`. Precision 0..18 inserts the decimal
 * point without multiplying or dividing through a floating-point type. A non-negative amount
 * carries no `+` sign — the existing product copy (`12.34 CNY`) is the frozen shape.
 */
fun presentationAmountText(
    minorUnits: Long,
    currency: CurrencyUnit,
): String {
    require(currency.precision in 0..18) { "currency precision must be in 0..18" }
    val text = minorUnits.toString()
    val negative = minorUnits < 0L
    val magnitude = if (negative) text.substring(1) else text
    val rendered =
        if (currency.precision == 0) {
            magnitude
        } else {
            val padded = magnitude.padStart(currency.precision + 1, '0')
            val cut = padded.length - currency.precision
            padded.substring(0, cut) + "." + padded.substring(cut)
        }
    return (if (negative) "-" else "") + rendered + " " + currency.code
}

/** One exact amount value, retained as minor units so no later step has to parse the text back. */
data class PresentationAmount(
    val minorUnits: Long,
    val currency: CurrencyUnit,
) {
    val text: String get() = presentationAmountText(minorUnits, currency)
}

// ---------------------------------------------------------------------------------------------
// Absent-value states
// ---------------------------------------------------------------------------------------------

/**
 * The five absent states. This is a separate supertype so the absent-copy renderer below accepts
 * only a member of this closed set: a present value cannot be passed to it at compile time, which
 * is what stops a real amount from silently rendering as an empty string.
 */
sealed interface PresentationAbsence

/**
 * The six display outcomes, each with its own state. `Present` holding zero is the REAL ZERO: a
 * ledger that stated `0.00 CNY` is never rendered through an absence branch.
 */
sealed interface PresentationValue<out T> {
    data object NotLoaded : PresentationValue<Nothing>, PresentationAbsence

    data object Failed : PresentationValue<Nothing>, PresentationAbsence

    data object NoRecords : PresentationValue<Nothing>, PresentationAbsence

    data object Unclassified : PresentationValue<Nothing>, PresentationAbsence

    data object NotEligible : PresentationValue<Nothing>, PresentationAbsence

    data class Present<T>(
        val value: T,
    ) : PresentationValue<T>
}

/**
 * The frozen copy of one absent state. The parameter type is [PresentationAbsence], so a caller
 * holding a [PresentationValue.Present] has no overload to call and gets a compile-time error
 * instead of an empty string.
 */
fun presentationValueText(value: PresentationAbsence): String =
    when (value) {
        PresentationValue.NotLoaded -> "未加载"
        PresentationValue.Failed -> "读取失败"
        PresentationValue.NoRecords -> "无记录"
        PresentationValue.Unclassified -> "未分类"
        PresentationValue.NotEligible -> "无对账资格"
    }

/** True only for a stated value that happens to be zero — never for an absence branch. */
fun isRealZero(value: PresentationValue<PresentationAmount>): Boolean =
    when (value) {
        is PresentationValue.Present -> value.value.minorUnits == 0L
        else -> false
    }

// ---------------------------------------------------------------------------------------------
// Categories
// ---------------------------------------------------------------------------------------------

/**
 * One resolved category, carrying the stable id (the later tint key), the current name and the
 * hierarchy path. `displayName` adds the parent path only when another category shares the same
 * second-level name, so equal names are never guessed apart.
 */
data class PresentationCategory(
    val categoryId: CategoryId,
    val name: String,
    val parentName: String?,
    val pathText: String,
    val displayName: String,
)

/**
 * The category outcome of one row. [NotLoaded] is a catalog that has not arrived; a category-bearing
 * leg (a hidden EXPENSE/INCOME posting account) whose account has no category mapping sets
 * [Resolved.hasUnclassified] instead of inventing a category.
 *
 * Classification obligation follows the account kind: only `EXPENSE`/`INCOME` accounts have one.
 * Real `ASSET`/`LIABILITY` legs and `EQUITY` legs legitimately carry no `posting_account_id` row,
 * so they never become 未分类. The authority is the domain [LedgerCatalog]'s own accounts; when the
 * catalog is absent or the account is unknown, the leg stays fail-closed as unclassified.
 */
sealed interface CategoryProjection {
    data object NotLoaded : CategoryProjection

    data class Resolved(
        val categories: List<PresentationCategory>,
        val hasUnclassified: Boolean,
    ) : CategoryProjection
}

private data class CategoryNode(
    val categoryId: CategoryId,
    val parentId: CategoryId?,
    val name: String,
    val postingAccountId: String?,
)

/**
 * Resolve one row's categories from the catalog association only: `catalog_category.posting_account_id`
 * matched against each posting's `accountId`. The snapshot is the UI-side cache; the domain
 * catalog is the fallback source. Association candidates are sorted by stable [CategoryId] text and
 * installed first-wins, so two categories claiming the same posting account always resolve to the
 * smaller id regardless of input order.
 *
 * The result preserves the postings' own order (first occurrence wins), so the home badges can
 * follow the posting order instead of an arbitrary map order. A leg whose account kind is
 * `EXPENSE`/`INCOME` and has no category mapping marks `hasUnclassified`; real `ASSET`/`LIABILITY`
 * and `EQUITY` legs carry no category by design. An absent catalog or an account missing from it
 * stays fail-closed (unclassified), so a missing authority never reads as a clean classification.
 */
fun projectCategories(
    postings: List<Posting>,
    snapshot: CatalogSnapshotView?,
    catalog: LedgerCatalog?,
): CategoryProjection {
    val nodes =
        when {
            snapshot != null ->
                snapshot.categories.map { view ->
                    CategoryNode(view.categoryId, view.parentId, view.name, view.postingAccountId?.value)
                }
            catalog != null ->
                catalog.categories.map { category ->
                    CategoryNode(category.id, category.parentId, category.name, category.postingAccountId?.value)
                }
            else -> return CategoryProjection.NotLoaded
        }
    val byAccount = LinkedHashMap<String, CategoryNode>()
    for (node in nodes.filter { it.postingAccountId != null }.sortedBy { it.categoryId.value }) {
        byAccount.putIfAbsent(requireNotNull(node.postingAccountId), node)
    }
    val byId = nodes.associateBy { it.categoryId }
    val nameCounts = nodes.filter { it.parentId != null }.groupingBy { it.name }.eachCount()
    // The catalog, not the posting list, decides which kinds owe a category.
    val accounts = catalog?.accounts?.associateBy { it.id }.orEmpty()
    val ordered = LinkedHashMap<CategoryId, PresentationCategory>()
    var hasUnclassified = false
    for (posting in postings) {
        val node = byAccount[posting.accountId.value]
        if (node == null) {
            val account = accounts[posting.accountId]
            // A known EXPENSE/INCOME account owes one category. An unknown account (or no catalog
            // at all) also stays unclassified: the authority is unavailable, so fail closed.
            val owesCategory =
                account == null || account.kind == AccountKind.EXPENSE || account.kind == AccountKind.INCOME
            if (owesCategory) hasUnclassified = true
            continue
        }
        if (ordered.containsKey(node.categoryId)) continue
        val parent = node.parentId?.let { byId[it] }
        val path = listOfNotNull(parent?.name, node.name).joinToString(" > ")
        val displayName = if (node.parentId != null && (nameCounts[node.name] ?: 0) > 1) path else node.name
        ordered[node.categoryId] =
            PresentationCategory(
                categoryId = node.categoryId,
                name = node.name,
                parentName = parent?.name,
                pathText = path,
                displayName = displayName,
            )
    }
    return CategoryProjection.Resolved(ordered.values.toList(), hasUnclassified)
}

/**
 * Deterministic tint slot for a stable [CategoryId]. The mapping is a 32-bit FNV-1a fold over the
 * id's UTF-8 bytes, so Android, Desktop and the JVM tests choose the same slot for the same id.
 * The caller owns the palette; this contract only fixes which slot a category lands in.
 */
fun presentationCategoryTintSlot(
    categoryId: CategoryId,
    slotCount: Int,
): Int {
    require(slotCount > 0) { "tint slot count must be positive" }
    var hash = -2128831035 // 2166136261 as a signed Int
    for (byte in categoryId.value.encodeToByteArray()) {
        hash = hash xor (byte.toInt() and 0xFF)
        hash *= 16777619
    }
    return ((hash.toLong() and 0xFFFFFFFFL) % slotCount).toInt()
}

// ---------------------------------------------------------------------------------------------
// Home row
// ---------------------------------------------------------------------------------------------

/** Which source produced the home row's main title. */
enum class HomeTitleSource {
    NOTE,
    CATEGORY,
    BUSINESS_TYPE,
}

/** One exact amount line bound to the real account that produced it. */
data class HomeAmountLine(
    val accountId: AccountId,
    val accountName: String,
    val amount: PresentationAmount,
)

/** The exact amount lines a home row renders, or the state that prevented them. */
sealed interface HomeAmountProjection {
    data object NotLoaded : HomeAmountProjection

    data object Failed : HomeAmountProjection

    data class Lines(
        val lines: List<HomeAmountLine>,
    ) : HomeAmountProjection
}

/**
 * One home flow row's presentation: main title, ordered category badges, exact amount lines and
 * both business times.
 *
 * - main title = first non-blank note, else the single resolved category name, else the Chinese
 *   business type; only `EXPENSE`/`INCOME` legs participate in the 未分类 check (real ASSET/LIABILITY
 *   and EQUITY legs are categoryless by design);
 * - badges = resolved categories in posting order, capped at three, with the remaining distinct
 *   count stated rather than dropped silently; [hasUnclassified] is the stable badge state for a
 *   row whose category-bearing leg has no mapping;
 * - every real `ASSET`/`LIABILITY` leg is one line in posting order. No kind ever merges multiple
 *   real legs — a transfer and a mixed payment both keep each real account's exact signed amount —
 *   while a row with a single real leg still renders exactly one line. Currencies never combine.
 */
data class HomeRowProjection(
    val title: String,
    val titleSource: HomeTitleSource,
    val categoryBadges: List<PresentationCategory>,
    val remainingCategoryCount: Int,
    val hasUnclassified: Boolean,
    val amounts: HomeAmountProjection,
    val times: PresentationTimes,
)

fun projectHomeRow(
    row: LedgerEntryRow,
    snapshot: CatalogSnapshotView?,
    catalog: LedgerCatalog?,
): HomeRowProjection {
    val categories = projectCategories(row.postings, snapshot, catalog)
    val resolved = categories as? CategoryProjection.Resolved
    val titleSource: HomeTitleSource
    val title: String
    val note = row.note?.takeIf { it.isNotBlank() }
    when {
        note != null -> {
            titleSource = HomeTitleSource.NOTE
            title = note
        }
        resolved != null && !resolved.hasUnclassified && resolved.categories.size == 1 -> {
            titleSource = HomeTitleSource.CATEGORY
            title = resolved.categories.single().displayName
        }
        else -> {
            titleSource = HomeTitleSource.BUSINESS_TYPE
            title = transactionKindBusinessName(row.kind)
        }
    }
    val badges = resolved?.categories?.take(MAX_HOME_CATEGORY_BADGES).orEmpty()
    val remaining = ((resolved?.categories?.size ?: 0) - MAX_HOME_CATEGORY_BADGES).coerceAtLeast(0)
    return HomeRowProjection(
        title = title,
        titleSource = titleSource,
        categoryBadges = badges,
        remainingCategoryCount = remaining,
        hasUnclassified = resolved?.hasUnclassified == true,
        amounts = projectHomeAmounts(row, catalog),
        times = presentationTimes(row.occurredAt, row.statisticsAt),
    )
}

private const val MAX_HOME_CATEGORY_BADGES = 3

private fun projectHomeAmounts(
    row: LedgerEntryRow,
    catalog: LedgerCatalog?,
): HomeAmountProjection {
    if (catalog == null) return HomeAmountProjection.NotLoaded
    val accounts = catalog.accounts.associateBy { it.id }
    val lines = ArrayList<HomeAmountLine>(row.postings.size)
    for (posting in row.postings) {
        val account = accounts[posting.accountId] ?: return HomeAmountProjection.Failed
        val real = account.realAccount && (account.kind == AccountKind.ASSET || account.kind == AccountKind.LIABILITY)
        if (!real) continue
        lines.add(
            HomeAmountLine(
                accountId = account.id,
                accountName = account.name,
                amount = PresentationAmount(posting.amount.minorUnits, posting.amount.currency),
            ),
        )
    }
    return HomeAmountProjection.Lines(lines)
}

private fun checkedPresentationAdd(
    left: Long,
    right: Long,
): Long? {
    if (right > 0 && left > Long.MAX_VALUE - right) return null
    if (right < 0 && left < Long.MIN_VALUE - right) return null
    return left + right
}

// ---------------------------------------------------------------------------------------------
// Category shares (horizontal proportion)
// ---------------------------------------------------------------------------------------------

/** One ranked category's exact amount; [amountMinorUnits] is a non-negative magnitude. */
data class CategoryShareInput(
    val categoryId: CategoryId,
    val name: String,
    val amountMinorUnits: Long,
)

/** One row of the exact table plus its fixed one-decimal percentage. */
data class CategoryShareRow(
    val categoryId: CategoryId,
    val name: String,
    val amountMinorUnits: Long,
    /** Tenths of a percent: `333` means `33.3%`. */
    val tenthsOfPercent: Long,
) {
    fun percentText(): String {
        val whole = tenthsOfPercent / 10
        val fraction = tenthsOfPercent % 10
        return "$whole.$fraction%"
    }
}

enum class CategoryShareFailure {
    NEGATIVE_AMOUNT,
    ZERO_DENOMINATOR,
    OVERFLOW,
}

/**
 * The horizontal proportion result. A failure is explicit ("no chart") and the caller keeps the
 * exact table; a ratio is never silently replaced by a zero-height bar or an empty chart.
 */
sealed interface CategoryShareProjection {
    data object NoCategories : CategoryShareProjection

    data class Chart(
        val rows: List<CategoryShareRow>,
        val denominatorMinorUnits: Long,
    ) : CategoryShareProjection

    data class NoChart(
        val reason: CategoryShareFailure,
    ) : CategoryShareProjection
}

/**
 * Builds the ranked share table. Arithmetic is rational throughout: a percentage is computed as
 * `round(numerator * 1000 / denominator)` in exact integer steps (no `toDouble()`), one decimal
 * place, half-up rounding. A single category is exactly `100.0%`; negative amounts, a zero
 * denominator and any intermediate overflow return [CategoryShareProjection.NoChart].
 *
 * Rows are ordered by amount descending, then by stable [CategoryId] text; the exact table stays
 * available and the caller renders it alone whenever the chart is refused.
 */
fun projectCategoryShares(entries: List<CategoryShareInput>): CategoryShareProjection {
    if (entries.isEmpty()) return CategoryShareProjection.NoCategories
    if (entries.any { it.amountMinorUnits < 0L }) {
        return CategoryShareProjection.NoChart(CategoryShareFailure.NEGATIVE_AMOUNT)
    }
    var denominator = 0L
    for (entry in entries) {
        denominator =
            checkedPresentationAdd(denominator, entry.amountMinorUnits)
                ?: return CategoryShareProjection.NoChart(CategoryShareFailure.OVERFLOW)
    }
    if (denominator == 0L) return CategoryShareProjection.NoChart(CategoryShareFailure.ZERO_DENOMINATOR)
    val rows = ArrayList<CategoryShareRow>(entries.size)
    for (entry in entries) {
        val tenths =
            fixedTenths(entry.amountMinorUnits, denominator)
                ?: return CategoryShareProjection.NoChart(CategoryShareFailure.OVERFLOW)
        rows.add(CategoryShareRow(entry.categoryId, entry.name, entry.amountMinorUnits, tenths))
    }
    rows.sortWith(compareByDescending<CategoryShareRow> { it.amountMinorUnits }.thenBy { it.categoryId.value })
    return CategoryShareProjection.Chart(rows, denominator)
}

/**
 * `round(numerator * 1000 / denominator)` as tenths of a percent, without a floating-point step.
 * `numerator` is guaranteed not to exceed `denominator`, so the quotient is 0 or 1 and the scaled
 * remainder is the only arithmetic that can overflow; that overflow is refused as `null`.
 */
private fun fixedTenths(
    numerator: Long,
    denominator: Long,
): Long? {
    if (numerator < 0L || numerator > denominator || denominator <= 0L) return null
    val quotient = numerator / denominator
    val remainder = numerator % denominator
    if (remainder == 0L) return quotient * 1000L
    if (remainder > (Long.MAX_VALUE - denominator / 2) / 1000L) return null
    val scaled = remainder * 1000L + denominator / 2
    return quotient * 1000L + scaled / denominator
}
