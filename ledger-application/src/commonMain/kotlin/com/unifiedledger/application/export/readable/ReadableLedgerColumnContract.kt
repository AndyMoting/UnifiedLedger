package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.TransactionKind
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/*
 * P8-05 批 a column contract (D-331 spec §3.4/§3.3 D-E4/D-E5/D-E7; single constant source for
 * the writer and the tests). Pure commonMain functions: two Shanghai wall-clock text columns,
 * the frozen kind display map, the per-kind presentation table, the name/category resolution
 * and the OOXML cell-length bound. No app-ui type, no JVM type, no I/O.
 *
 * Amount sign convention (D-E5 preamble, golden-backed): expense/spend-class legs carry their
 * ORIGINAL positive sign and income/funding-source legs their ORIGINAL negative sign — the
 * export never applies the detail-row's by-kind flip (`P503LedgerView` renders EXPENSE
 * negative/INCOME positive; this file does not). Fund-flow direction is decided by kind
 * semantics and leg roles, never by the sign alone (`Money.ofMinor` is an unsigned wrapper).
 */

/** The frozen OOXML cell text bound; one character over it fails the export with NOTE_TOO_LONG. */
const val READABLE_EXPORT_MAX_CELL_CHARS: Int = 32_767

/** The single header source for the ten frozen columns A..J (§3.4; the writer writes these verbatim). */
val READABLE_EXPORT_HEADERS: List<String> =
    listOf(
        "凭证时间",
        "统计时间",
        "类型",
        "金额",
        "币种",
        "账户1",
        "账户2",
        "分类",
        "备注",
        "来源",
    )

/** D-E7: the frozen sheet display name (display copy only; the reader resolves the first sheet by relationship). */
const val READABLE_EXPORT_SHEET_NAME: String = "账目导出"

/** D-E7: the suggested file name prefix (display only; the platform picker may override it). */
const val READABLE_EXPORT_SUGGESTED_NAME_PREFIX: String = "unifiedledger-export-"

/** D-E7: the suggested file name suffix (display only). */
const val READABLE_EXPORT_SUGGESTED_NAME_SUFFIX: String = ".xlsx"

/** D-E7: the fixed account placeholder — never a bare account id. */
const val READABLE_EXPORT_UNNAMED_ACCOUNT: String = "未命名账户"

/** D-E7: the fixed category placeholder — never a bare category id. */
const val READABLE_EXPORT_UNNAMED_CATEGORY: String = "未命名分类"

/** D-E7/D-E5: the row-level placeholder written when the real leg shape is outside the frozen table. */
const val READABLE_EXPORT_PRESENTATION_UNRESOLVED_TEXT: String = "（呈现未定）"

/**
 * D-E4: the frozen kind -> display-name map for ALL 16 kinds. The six precedent values are
 * copied verbatim from the existing UI surfaces (`P503RefundEntryPresentation.kt:27-32`,
 * `P503ConfirmationPresentation.kt:49-51`); the ten coined names are user-ruling U7. The
 * detail page keeps its raw `kind.name` rendering (zero blast radius, D-E4 tail).
 */
fun readableExportKindLabel(kind: TransactionKind): String =
    when (kind) {
        TransactionKind.OPENING_BALANCE -> "期初余额"
        TransactionKind.EXPENSE -> "支出"
        TransactionKind.INCOME -> "收入"
        TransactionKind.ACCOUNT_TRANSFER -> "转账"
        TransactionKind.CREDIT_REPAYMENT -> "信用还款"
        TransactionKind.REFUND_RECEIPT -> "退款到账"
        TransactionKind.BALANCE_ADJUSTMENT -> "余额调整"
        TransactionKind.BALANCE_ADJUSTMENT_REVERSAL -> "余额调整冲正"
        TransactionKind.STORED_VALUE_RECHARGE -> "储值充值"
        TransactionKind.STORED_VALUE_SPEND -> "储值消费"
        TransactionKind.STORED_VALUE_EXPIRY_LOSS -> "储值过期损失"
        TransactionKind.STORED_VALUE_PRE_ACTIVATION_BALANCE_ADJUSTMENT -> "激活前余额调整"
        TransactionKind.LEND -> "借出"
        TransactionKind.COLLECT -> "收回"
        TransactionKind.PREPAID_PURCHASE -> "预付购买"
        TransactionKind.PREPAID_RECOGNITION -> "预付确认"
    }

/**
 * U3/D-138: one Shanghai wall-clock text column value, `yyyy-MM-dd HH:mm:ss`. The frozen zone is
 * [MonthlyBuckets.REPORT_TIME_ZONE] (Asia/Shanghai, no DST); year is zero-padded to four digits
 * so early-era instants round-trip.
 */
fun readableExportWallClockText(instant: Instant): String {
    val local = instant.toLocalDateTime(MonthlyBuckets.REPORT_TIME_ZONE)
    val year = local.year.toString().padStart(4, '0')
    val month = local.monthNumber.toString().padStart(2, '0')
    val day = local.dayOfMonth.toString().padStart(2, '0')
    val hour = local.hour.toString().padStart(2, '0')
    val minute = local.minute.toString().padStart(2, '0')
    val second = local.second.toString().padStart(2, '0')
    return "$year-$month-$day $hour:$minute:$second"
}

/** Inverse of [readableExportWallClockText] for the jvmTest round-trip vector (V-2); null when out of shape. */
fun parseReadableExportWallClockTextOrNull(text: String): Instant? {
    val match = READABLE_EXPORT_WALL_CLOCK_REGEX.matchEntire(text.trim()) ?: return null
    val group = match.groupValues
    return try {
        LocalDateTime(
            year = group[1].toInt(),
            monthNumber = group[2].toInt(),
            dayOfMonth = group[3].toInt(),
            hour = group[4].toInt(),
            minute = group[5].toInt(),
            second = group[6].toInt(),
        ).toInstant(MonthlyBuckets.REPORT_TIME_ZONE)
    } catch (failure: IllegalArgumentException) {
        null
    }
}

private val READABLE_EXPORT_WALL_CLOCK_REGEX =
    Regex("""(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})""")

/**
 * One resolved export row (§3.4 A..J); the writer renders it 1:1. Empty string = the column's
 * frozen empty value (an empty inline cell). [presentationResolved] == false marks a D-E5
 * fall-through: the 金额 cell carries the fixed 「（呈现未定）」 placeholder TEXT (§3.4 D:
 * 恒非空, the placeholder row is the non-empty content), 币种/账户1/账户2/分类 stay empty, and
 * the 类型 cell keeps the real kind label — counted by the caller, never silently invented
 * (D-032).
 */
data class ReadableExportRowView(
    val occurredAtText: String,
    val statisticsAtText: String,
    val kindLabel: String,
    /** Exact signed decimal text of the amount column; the fixed placeholder when unresolved. */
    val amountText: String,
    /** Whether [amountText] renders as a numeric cell (true) or the placeholder text cell. */
    val presentationResolved: Boolean,
    val currencyCode: String,
    val account1Name: String,
    val account2Name: String,
    val categoryPathText: String,
    val noteText: String,
    val sourceLabel: String,
)

/**
 * D-E5 presentation outcome for one transaction: the picked legs plus whether the frozen table
 * actually covered the shape. `amountMinor` keeps the ledger sign; `currency` is the amount
 * leg's own currency (group ② cross-currency transfers take the account-1 outflow leg without
 * inventing a conversion). `categoryAnchorAccountId` is the account whose category mapping
 * anchors the 分类 column (the amount leg for ①⑤⑥⑦, the fee leg for ②, none for ③④).
 */
data class ReadableExportLegPresentation(
    val resolved: Boolean,
    val amountMinor: Long? = null,
    val currency: CurrencyUnit? = null,
    val account1: AccountId? = null,
    val account2: AccountId? = null,
    val categoryAnchorAccountId: AccountId? = null,
)

/** The per-leg facts the presentation table consumes (account kind + category mapping). */
data class ReadableExportLegFacts(
    val posting: Posting,
    val accountKind: AccountKind?,
    /** `catalog_category.posting_account_id` mapping of this leg's account (the 分类腿 when non-null). */
    val categoryId: CategoryId?,
)

/**
 * D-E5: the frozen per-kind presentation table over the CURRENT version's leg facts (already in
 * posting order). The table is shape-driven: any leg set outside the frozen per-group shapes
 * falls through to the row-level placeholder (D-E10 PRESENTATION_UNRESOLVED), never to a best
 * guess (D-032).
 */
fun resolveReadableExportPresentation(
    kind: TransactionKind,
    legs: List<ReadableExportLegFacts>,
): ReadableExportLegPresentation {
    val categoryLegs = legs.filter { it.categoryId != null }
    val nonCategoryLegs = legs.filter { it.categoryId == null }
    return when (kind) {
        TransactionKind.EXPENSE,
        TransactionKind.INCOME,
        ->
            // ① MonthlyBuckets semantics: one category leg (the fund-flow side) + one fund leg.
            singleCategoryLegShape(legs, categoryLegs, nonCategoryLegs)

        TransactionKind.ACCOUNT_TRANSFER,
        TransactionKind.LEND,
        TransactionKind.COLLECT,
        TransactionKind.CREDIT_REPAYMENT,
        ->
            // ② rg-03/rg-08: the negative non-category outflow leg is the amount; the unique
            // remaining non-category leg is the counterpart; the fee category leg (posting
            // order tie-break) anchors 分类 even though it is excluded from the amount.
            transferShape(legs, categoryLegs, nonCategoryLegs)

        TransactionKind.BALANCE_ADJUSTMENT,
        TransactionKind.BALANCE_ADJUSTMENT_REVERSAL,
        ->
            // ③ rg-09: one non-equity (asset) leg + one equity counterpart, both non-category.
            // Golden: rg-09.json:328-340 (+30/−30) and :364-376 (reversal −20/+20).
            singleNonEquityShape(legs, categoryLegs, nonCategoryLegs)

        TransactionKind.OPENING_BALANCE,
        TransactionKind.STORED_VALUE_RECHARGE,
        TransactionKind.STORED_VALUE_PRE_ACTIVATION_BALANCE_ADJUSTMENT,
        ->
            // ④ rg-01/rg-10: one non-equity asset leg + one remaining leg. A bonus-income
            // recharge (two non-equity legs) is NOT covered — rg-10:645-667's three-leg shape
            // falls through (the registered PRESENTATION_UNRESOLVED vector). Golden: rg-01
            // :175-188 (+1000/−1000), rg-10:645-652 single recharge (+1200 on the asset leg).
            singleNonEquityShape(legs, categoryLegs, nonCategoryLegs)

        TransactionKind.STORED_VALUE_SPEND,
        TransactionKind.STORED_VALUE_EXPIRY_LOSS,
        TransactionKind.PREPAID_PURCHASE,
        TransactionKind.PREPAID_RECOGNITION,
        ->
            // ⑤/⑥ rg-10/rg-11: the category leg is the amount leg (positive expense side) and
            // the unique remaining leg is the outflow counterpart. Golden: rg-10:1742-1758
            // (+300/−300), rg-11:901-919 (+33.33/−33.33 with category-main-service).
            //
            // REGISTERED SPEC-VS-GOLDEN DEVIATION (lead finding on batch a): the spec's D-E5 ⑤
            // lists PREPAID_PURCHASE in this 「金额 = 分类腿」 group, but its GOLDEN leg shape
            // (rg-11 states/main-01, `main-purchase-postings`) is payment_asset −100.00 (cash) +
            // prepaid_asset +100.00 with NO category leg — the ⑤ contract cannot resolve it and
            // the row falls through to the row-level 「（呈现未定）」 placeholder, counted as
            // PRESENTATION_UNRESOLVED (D-032: never invent a category-anchored amount). The real
            // database vector in ReadableExportRealLedgerReconciliationTest pins this; the spec
            // text correction rides the lead's revision 3.
            singleCategoryLegShape(legs, categoryLegs, nonCategoryLegs)

        TransactionKind.REFUND_RECEIPT ->
            // ⑦ rg-07: the category leg keeps its possibly-negative original sign (a refund is
            // a negative expense); the unique fund leg is the counterpart.
            singleCategoryLegShape(legs, categoryLegs, nonCategoryLegs)
    }
}

/** Groups ①⑤⑥⑦: exactly one category leg + exactly one non-category leg, nothing else. */
private fun singleCategoryLegShape(
    legs: List<ReadableExportLegFacts>,
    categoryLegs: List<ReadableExportLegFacts>,
    nonCategoryLegs: List<ReadableExportLegFacts>,
): ReadableExportLegPresentation {
    val categoryLeg = categoryLegs.singleOrNull() ?: return ReadableExportLegPresentation(resolved = false)
    val counterpart = nonCategoryLegs.singleOrNull() ?: return ReadableExportLegPresentation(resolved = false)
    if (legs.size != 2) return ReadableExportLegPresentation(resolved = false)
    return ReadableExportLegPresentation(
        resolved = true,
        amountMinor = categoryLeg.posting.amount.minorUnits,
        currency = categoryLeg.posting.amount.currency,
        account1 = categoryLeg.posting.accountId,
        account2 = counterpart.posting.accountId,
        categoryAnchorAccountId = categoryLeg.posting.accountId,
    )
}

/** Group ②: the negative non-category outflow leg carries the amount; a fee category leg anchors 分类. */
private fun transferShape(
    legs: List<ReadableExportLegFacts>,
    categoryLegs: List<ReadableExportLegFacts>,
    nonCategoryLegs: List<ReadableExportLegFacts>,
): ReadableExportLegPresentation {
    if (nonCategoryLegs.size != 2 || legs.size > 3) return ReadableExportLegPresentation(resolved = false)
    val outflow =
        nonCategoryLegs.firstOrNull { it.posting.amount.minorUnits < 0L }
            ?: return ReadableExportLegPresentation(resolved = false)
    val inflow =
        nonCategoryLegs.firstOrNull { it.posting.accountId != outflow.posting.accountId }
            ?: return ReadableExportLegPresentation(resolved = false)
    val feeLeg = categoryLegs.minByOrNull { it.posting.id.value }
    return ReadableExportLegPresentation(
        resolved = true,
        amountMinor = outflow.posting.amount.minorUnits,
        currency = outflow.posting.amount.currency,
        account1 = outflow.posting.accountId,
        account2 = inflow.posting.accountId,
        categoryAnchorAccountId = feeLeg?.posting?.accountId,
    )
}

/** Groups ③④: exactly one non-equity (asset) leg + one remaining leg; both non-category. */
private fun singleNonEquityShape(
    legs: List<ReadableExportLegFacts>,
    categoryLegs: List<ReadableExportLegFacts>,
    nonCategoryLegs: List<ReadableExportLegFacts>,
): ReadableExportLegPresentation {
    if (categoryLegs.isNotEmpty() || legs.size != 2) return ReadableExportLegPresentation(resolved = false)
    val assetLeg =
        nonCategoryLegs
            .singleOrNull { it.accountKind == AccountKind.ASSET }
            ?: return ReadableExportLegPresentation(resolved = false)
    val counterpart =
        nonCategoryLegs.singleOrNull { it.posting.accountId != assetLeg.posting.accountId }
            ?: return ReadableExportLegPresentation(resolved = false)
    return ReadableExportLegPresentation(
        resolved = true,
        amountMinor = assetLeg.posting.amount.minorUnits,
        currency = assetLeg.posting.amount.currency,
        account1 = assetLeg.posting.accountId,
        account2 = counterpart.posting.accountId,
    )
}

/**
 * D: the exact decimal text of signed minor units (the `formatMinorUnits` integer-position
 * family, no binary float anywhere). Negative zero cannot exist on a Long.
 */
fun readableExportAmountText(
    minorUnits: Long,
    precision: Int,
): String {
    require(precision in 0..18) { "currency precision must be in 0..18" }
    val sign = if (minorUnits < 0L) "-" else ""
    val magnitude =
        when {
            minorUnits == Long.MIN_VALUE -> Long.MAX_VALUE
            minorUnits < 0L -> -minorUnits
            else -> minorUnits
        }
    val digits = magnitude.toString()
    if (precision == 0) return sign + digits
    val padded = digits.padStart(precision + 1, '0')
    val whole = padded.substring(0, padded.length - precision)
    val fraction = padded.substring(padded.length - precision)
    return sign + whole + "." + fraction
}

/**
 * F/G: the account display name — the catalog CURRENT name, or the fixed 未命名账户 placeholder
 * when the catalog has no row or the row is blank-named. Never a bare account id (D-E7); null
 * only for an absent leg (the 账户2 empty value).
 */
fun readableExportAccountName(
    catalog: LedgerCatalog?,
    accountId: AccountId?,
): String? {
    if (accountId == null) return null
    val name = catalog?.accounts?.firstOrNull { it.id == accountId }?.name
    return if (name.isNullOrEmpty()) READABLE_EXPORT_UNNAMED_ACCOUNT else name
}

/**
 * H: the anchored leg's full category path 「一级/二级」 from `posting_account_id` -> leaf
 * category -> parent chain, using the catalog snapshot names. Null when the leg has no mapping
 * (the column's empty value); the fixed placeholder when a mapped name is missing/blank (never
 * a bare id, D-E7). A deeper chain keeps walking to the root and joins every level (the
 * full-path disambiguation discipline).
 */
fun readableExportCategoryPath(
    catalog: LedgerCatalog?,
    accountId: AccountId?,
): String? {
    if (accountId == null) return null
    val leaf =
        catalog?.categories?.firstOrNull { it.postingAccountId == accountId }
            ?: return null
    val segments = mutableListOf<String>()
    var current: Category? = leaf
    var guard = 0
    while (current != null) {
        segments.add(0, current.name.ifBlank { READABLE_EXPORT_UNNAMED_CATEGORY })
        current = current.parentId?.let { parentId -> catalog.categories.firstOrNull { it.id == parentId } }
        guard++
        if (guard > MAX_CATEGORY_DEPTH) return null
    }
    if (segments.isEmpty()) return null
    return segments.joinToString("/")
}

private const val MAX_CATEGORY_DEPTH: Int = 16

/**
 * J: the source label from the batched lineage read (D-E6 precedence: import confirmation ->
 * manual receipt -> unmarked).
 */
fun readableExportSourceLabel(entry: CreationEntry): String = entry.label

/**
 * R6/D-E10: the note gate. A note within the OOXML bound passes through (null = the column's
 * empty value); anything over the bound is a typed NOTE_TOO_LONG export failure, never a
 * truncation.
 */
fun readableExportNoteWithinBound(note: String?): Boolean = note == null || note.length <= READABLE_EXPORT_MAX_CELL_CHARS

/**
 * D-E12: the self-owned cooperative cancellation signal (`BackupExport.kt:84-87` shape), checked
 * per page by the use case. Zero app-ui types; 批 b bridges the platform signal onto this.
 */
fun interface ReadableExportCancellation {
    fun isCancelled(): Boolean
}

/**
 * D-E12: the self-owned operation-lease face, decoupled from any concrete runtime owner (the
 * app-ui `LedgerRuntimeOwner.acquireLease` outcome shape, mirrored without the app-ui type):
 * non-blocking acquisition, `RuntimeNotReady` when the runtime is busy/closed, the lease released
 * exactly once on [ExportOperationLease.close]. Batch b's composition root bridges the real
 * owner onto this port.
 */
interface LedgerRuntimeLeasePort {
    fun acquireLease(): LedgerRuntimeLeasePortResult
}

/** The acquisition outcome (the `LeaseAcquireResult` shape, self-owned). */
sealed interface LedgerRuntimeLeasePortResult {
    data class Acquired(
        val lease: ExportOperationLease,
    ) : LedgerRuntimeLeasePortResult

    data object RuntimeNotReady : LedgerRuntimeLeasePortResult
}

/** One in-flight export's hold on the runtime; idempotent release. */
fun interface ExportOperationLease : AutoCloseable {
    override fun close()
}

/**
 * The row-level writer face the use case streams through (one header, then every resolved row,
 * then at most one commit; close is idempotent). The jvmMain XLSX writer implements this over a
 * [ReadableExportSink]; tests inject fakes. Kept in commonMain so the commonMain use case can
 * face it while the OOXML machinery stays jvmMain.
 */
interface ReadableExportSheetWriter : AutoCloseable {
    /** Writes the frozen ten-column header row (the single constant source). */
    fun writeHeader()

    /** Streams one data row. */
    fun writeRow(row: ReadableExportRowView)

    /** Publishes the workbook; must be called exactly once. */
    fun commit()
}
