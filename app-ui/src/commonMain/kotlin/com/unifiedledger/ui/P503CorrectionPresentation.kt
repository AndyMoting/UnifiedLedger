package com.unifiedledger.ui

import com.unifiedledger.application.CatalogSnapshotView
import com.unifiedledger.application.CreationEntry
import com.unifiedledger.application.RecycleBinDependencyLeg
import com.unifiedledger.application.RecycleBinResult
import com.unifiedledger.application.RecycleBinRow
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.P705FailureCode
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.VoidReason
import com.unifiedledger.domain.VoidReasonCode
import com.unifiedledger.domain.parseExactDecimal
import com.unifiedledger.domain.parseExactDecimalLenient
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

// P7-05 slice 1b pure presentation decisions (D-156; spec section 4.4). The composables stay thin
// renderers; every load-bearing decision — whether the detail page offers the correction/void
// entries, how the difference preview renders each field (amount with sign and currency code,
// category/account current names, both time-zone semantics), the recycle-bin row's reason / void
// time / dependency explanation and its restore admissibility, and every TalkBack label — is a
// pure function here, so it is assertable in app-ui's JVM tests without a Compose UI-test harness
// (the P503LedgerViewPresentation F6 precedent). No Compose or platform API is used in this file.
//
// Reason privacy (V-21): the void/restore reason and its optional note are rendered ONLY through
// these display builders, which the screens pass into visible text and `Modifier.semantics`. They
// must never reach a log line or a test failure message; no function here logs anything.

// ------------------------------------------------------------------ detail-page entry gate

/**
 * P7-05.B/C (D-156; DP-12; spec section 3.5): whether the read-only detail page offers the
 * correction and void entries. Both entries need the whole support-matrix row — the supported
 * kind AND the supported creation lineage — and an effective transaction: the detail read path
 * already filters voided transactions (spec section 3.6 #3), so [isEffective] is a defensive echo
 * of that invariant, never a second source of truth. [hasCorrectionOrigin] and [hasVoidTarget] are
 * the host-supplied affordances (the correction entry needs the host-resolved old-value snapshot,
 * which the screen cannot derive from the detail legs alone): a missing one renders no dead button
 * (the retained-read-failure F1 precedent).
 */
internal fun detailCorrectionAffordancesVisible(
    kind: TransactionKind,
    creationEntry: CreationEntry,
    isEffective: Boolean,
    hasCorrectionOrigin: Boolean,
    hasVoidTarget: Boolean,
): DetailCorrectionAffordances {
    val supported = com.unifiedledger.domain.isP705CorrectionSupportedKind(kind) && isP705CreationLineageSupported(creationEntry)
    return DetailCorrectionAffordances(
        edit = supported && isEffective && hasCorrectionOrigin,
        void = supported && isEffective && hasVoidTarget,
    )
}

/**
 * The creation-lineage half of the frozen first-slice support matrix (spec sections 2.2/3.1, V-13):
 * only manually created transactions are in this slice — the scope is `MANUAL_CREATED` or
 * `UNMARKED` ([com.unifiedledger.application.CreationEntry]), and an import-created transaction is
 * explicitly a later slice (DP-5). This mirrors the commit ports' own gate
 * (`importCreationConfirmationCount > 0 → P705_CREATION_LINEAGE_NOT_SUPPORTED`), so the detail page
 * never offers an entry the commit would type-reject. The authoritative lineage already rides the
 * detail payload ([com.unifiedledger.application.TransactionDetail.creationEntry]); the screen must
 * pass it in rather than gate on the kind alone.
 */
internal fun isP705CreationLineageSupported(creationEntry: CreationEntry): Boolean = creationEntry != CreationEntry.IMPORT_CREATED

/** The two independent detail-page entries; each is rendered only when its flag is set. */
internal data class DetailCorrectionAffordances(
    val edit: Boolean,
    val void: Boolean,
) {
    val any: Boolean get() = edit || void
}

// ------------------------------------------------------------------ difference preview

/** One rendered difference-preview line: the field's label and its old/new display text. */
internal data class TransactionEditDiffLine(
    val field: TransactionEditField,
    val label: String,
    val oldText: String,
    val newText: String,
    val changed: Boolean,
)

/** The frozen display label of one corrected field (spec section 3.2 field set). */
internal fun transactionEditFieldLabel(field: TransactionEditField): String =
    when (field) {
        TransactionEditField.NOTE -> "备注"
        TransactionEditField.STATISTICS_AT -> "统计时间"
        TransactionEditField.AMOUNT -> "金额"
        TransactionEditField.CATEGORY -> "分类"
        TransactionEditField.FUNDING_ACCOUNT -> "资金账户"
    }

/**
 * P7-05.B (spec section 4.4): the pure field-by-field difference preview rendering. Each frozen
 * first-slice field shows its old value against the new one: the amount with an explicit sign and
 * its currency code, the category/funding account as their CURRENT names (an id outside the
 * current catalog falls back to the stable id, and an absent reference shows 无), and the
 * statistics time with both time-zone semantics. A draft value that does not parse is shown
 * verbatim — the preview never normalizes silently (the commit rejects it typed). The preview is a
 * display-only read and is never commit permission (spec section 3.2).
 *
 * Currency assumption (disclosed, first slice): [currency] is the host-supplied currency and the
 * screen passes the ledger default. The corrected transaction's actual currency is not on the
 * host-resolved origin snapshot, and multi-currency correction is out of first-slice scope (spec
 * section 2.2), so the preview formats with the default and this is stated rather than hidden. When
 * the origin snapshot later carries the transaction's own currency the caller should pass that
 * instead; the amount code still prints the currency it was formatted with.
 */
internal fun transactionEditDiffLines(
    preview: TransactionEditPreview,
    categoryNames: Map<CategoryId, String>,
    accountNames: Map<AccountId, String>,
    currency: CurrencyUnit,
): List<TransactionEditDiffLine> =
    preview.fields.map { diff ->
        TransactionEditDiffLine(
            field = diff.field,
            label = transactionEditFieldLabel(diff.field),
            oldText = correctionFieldDisplayText(diff.field, diff.oldText, categoryNames, accountNames, currency),
            newText = correctionFieldDisplayText(diff.field, diff.newText, categoryNames, accountNames, currency),
            changed = diff.changed,
        )
    }

private fun correctionFieldDisplayText(
    field: TransactionEditField,
    raw: String,
    categoryNames: Map<CategoryId, String>,
    accountNames: Map<AccountId, String>,
    currency: CurrencyUnit,
): String =
    when (field) {
        TransactionEditField.NOTE -> if (raw.isEmpty()) "无" else raw
        TransactionEditField.STATISTICS_AT -> correctionTimeDisplayText(raw)
        TransactionEditField.AMOUNT -> correctionAmountDisplayText(raw, currency)
        TransactionEditField.CATEGORY -> categoryReferenceDisplayText(raw, categoryNames)
        TransactionEditField.FUNDING_ACCOUNT -> accountReferenceDisplayText(raw, accountNames)
    }

/**
 * The amount display of one preview side: the exact signed value at the currency precision plus
 * its currency code, so the sign and the currency are never lost (spec section 4.4). A text the
 * exact decimal parser does not accept is shown verbatim (the parser is the same strict-then-lenient
 * pair the entry flow uses, so no value the commit would accept is reformatted differently).
 */
internal fun correctionAmountDisplayText(
    raw: String,
    currency: CurrencyUnit,
): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return "无"
    val minorUnits =
        parseExactDecimal(trimmed, currency.precision)
            ?: parseExactDecimalLenient(trimmed, currency.precision)
            ?: return raw
    val sign = if (minorUnits > 0L) "+" else ""
    return "$sign${formatMinorUnits(minorUnits, currency.precision)} ${currency.code}"
}

/**
 * The current name of a category reference (the preview stores the stable id value): the current
 * catalog name when the catalog still offers the id, the stable id itself when it no longer does
 * (a reference the user must still see), and 无 when the field is absent.
 */
internal fun categoryReferenceDisplayText(
    raw: String,
    categoryNames: Map<CategoryId, String>,
): String = categoryNames.entries.firstOrNull { it.key.value == raw }?.value ?: if (raw.isEmpty()) "无" else raw

/** The account analogue of [categoryReferenceDisplayText] (current name, else the stable id, else 无). */
internal fun accountReferenceDisplayText(
    raw: String,
    accountNames: Map<AccountId, String>,
): String = accountNames.entries.firstOrNull { it.key.value == raw }?.value ?: if (raw.isEmpty()) "无" else raw

/**
 * The two time-zone semantics of one time value (spec section 4.4): the frozen Asia/Shanghai wall
 * clock with its `（UTC+8）` annotation next to the UTC ISO instant, exactly as the occurred-at
 * display freezes it. A value that is not a parseable instant is shown verbatim — the preview
 * never invents a conversion.
 */
internal fun correctionTimeDisplayText(raw: String): String {
    if (raw.isEmpty()) return "无"
    val instant =
        try {
            Instant.parse(raw)
        } catch (failure: IllegalArgumentException) {
            return raw
        }
    val local = instant.toLocalDateTime(occurredAtTimeZone)
    val wallClock =
        "${local.date} ${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
    return if (local.date.year > 1991) "$wallClock（UTC+8）＝ $instant" else wallClock
}

/** P7-05.B: the explicit confirmation statement of the correction page (spec section 4.4). */
internal const val TRANSACTION_EDIT_CONFIRM_STATEMENT: String = "确认后：历史版本保留、旧版本失效。"

// ------------------------------------------------------------------ void/restore reason

/** The frozen display label of one typed void/restore reason code (DP-11). */
internal fun voidReasonCodeLabel(code: VoidReasonCode): String =
    when (code) {
        VoidReasonCode.MIS_ENTERED -> "录入错误"
        VoidReasonCode.DUPLICATE_ENTRY -> "重复录入"
        VoidReasonCode.NO_LONGER_APPLICABLE -> "不再适用"
        VoidReasonCode.VOIDED_IN_ERROR -> "误作废"
        VoidReasonCode.OTHER -> "其他"
    }

/**
 * The admissibility of one reason draft (DP-11): `null` when the draft may be confirmed, otherwise
 * the frozen rejection code. It reuses the single domain rule
 * ([com.unifiedledger.domain.voidReasonRejection]) rather than restating the code set or the note
 * bound, so the page and the use case can never disagree. An empty note text is the draft's absent
 * representation; a non-empty text (including whitespace-only) is passed through so the domain's
 * blank rule still applies.
 */
internal fun voidReasonDraftRejection(draft: VoidReasonDraft): P705FailureCode? = com.unifiedledger.domain.voidReasonRejection(draft.code?.let { VoidReason(it, draft.note.ifEmpty { null }) })

/** The reason as it is shown in the confirmation page and the recycle bin (never logged, V-21). */
internal fun voidReasonDisplayText(reason: VoidReason): String = reason.note?.let { "${voidReasonCodeLabel(reason.code)}（$it）" } ?: voidReasonCodeLabel(reason.code)

/** P7-05.C: the impact statement of the void confirmation page (spec section 4.4). */
internal const val VOID_IMPACT_STATEMENT: String =
    "作废后：该交易将从月度与流水中移除、不再参与余额与报表，并出现在回收站中；可在回收站恢复。"

/** P7-05.C: the impact statement of the restore confirmation page (spec section 4.4). */
internal const val RESTORE_IMPACT_STATEMENT: String =
    "恢复后：该交易按原统计时间重新参与月度、流水、余额与报表。"

// ------------------------------------------------------------------ manual re-check status

/**
 * P7-05 (V-19; D-173): the visible status line of one manual lost-commit re-check, so the
 * re-check is never a silent no-op. [P705CommitCheckOutcome.NONE] means no manual re-check has
 * resolved yet — the reducer never resets `checkOutcome` to `NONE` (the re-check REQUEST event is
 * state-preserving by design, the P7-02 precedent), so it yields `null`: no extra text.
 * [P705CommitCheckOutcome.ABSENT] and [P705CommitCheckOutcome.UNAVAILABLE] mean the read-only
 * resolve found no row / could not read, and the surface stays unknown and re-checkable, so the
 * user is told the re-check ran and found nothing. Because `NONE` is only the never-resolved
 * state, the still-unknown line of a previous `ABSENT`/`UNAVAILABLE` outcome may remain visible
 * while a further re-check is in flight. The status line is rendered beside the
 * 「重新核对」 button by the three P7-05 submitting rows; a determinate hit never reaches here (it
 * leaves the surface as the existing result event).
 */
internal fun p705RecheckStatusText(outcome: P705CommitCheckOutcome): String? =
    when (outcome) {
        P705CommitCheckOutcome.NONE -> null
        P705CommitCheckOutcome.ABSENT -> "未找到该次提交记录，可再次核对"
        P705CommitCheckOutcome.UNAVAILABLE -> "暂时无法核对，请稍后重试"
    }

// ------------------------------------------------------------------ recycle bin rows

/** The recycle-bin list's row text set: the visible lines and the TalkBack label share one source. */
internal data class RecycleBinRowText(
    val title: String,
    val reason: String,
    val voidTime: String,
    val dependencies: String,
    val restoreStatus: String,
) {
    /** One announced node per row: the exact values stay TalkBack-reachable (the C04 precedent). */
    val contentDescription: String
        get() = "$title；原因 $reason；作废时间 $voidTime；$dependencies；$restoreStatus"
}

/**
 * P7-05.C (spec section 4.4; V-21): one recycle-bin row's reason, void time and dependency
 * explanation. The dependencies are the transaction's current-version account/category references
 * with their CURRENT names and admissibility, plus the effective-refund linkage and the creation
 * entry. The restore status is the row's frozen admissibility: admissible, or the typed rejection
 * the restore would produce. The list order is the query's frozen total order and is never
 * re-sorted here.
 */
internal fun recycleBinRowText(row: RecycleBinRow): RecycleBinRowText =
    RecycleBinRowText(
        title = "${row.voided.kind} ${row.voided.transactionId.value}",
        reason = voidReasonDisplayText(row.voided.voidReason),
        voidTime = correctionTimeDisplayText(row.voided.voidedAt.toString()),
        dependencies = recycleBinDependenciesText(row),
        restoreStatus = recycleBinRestoreStatusText(row),
    )

/** The dependency explanation line: referenced names with admissibility, refund linkage, creation entry. */
internal fun recycleBinDependenciesText(row: RecycleBinRow): String {
    val legs =
        if (row.dependencies.isEmpty()) {
            "无引用分录"
        } else {
            row.dependencies.joinToString("；") { leg -> recycleBinDependencyLegText(leg) }
        }
    val refund = if (row.hasEffectiveRefundLink) "存在有效关联退款" else "无有效关联退款"
    return "依赖：$legs；$refund；创建入口 ${row.creationEntry.label}"
}

/** One referenced leg: current name, admissibility, and its category mapping when one exists. */
internal fun recycleBinDependencyLegText(leg: RecycleBinDependencyLeg): String {
    val account = "${leg.accountName}（${if (leg.accountActive) "可用" else "已停用"}）"
    val category =
        when {
            leg.categoryName == null -> "无分类"
            leg.categoryActive == true -> leg.categoryName
            else -> "${leg.categoryName}（已停用）"
        }
    return "$account · $category"
}

/**
 * The presentation-layer restore verdict of one bin row (D-158 section 4; spec sections 3.3/4.4).
 * [RecycleBinRow.restoreRejectionCode] is the frozen application decision, but `QueryRecycleBin`
 * folds the void-only `P705_REFUND_LINKED_VOID_NOT_SUPPORTED` into it while the restore use case
 * never checks that code — D-158 section 4 registers exactly this and forbids slice 1b from
 * presenting it as a restore verdict. The refund linkage is a VOID precondition (DP-13), never a
 * restore one, so it is filtered out here and the row falls back to the admissible presentation.
 * This is a presentation correction only: the frozen query contract (which declares the refund
 * linkage part of `restoreRejectionCode`) is left untouched, and every other code is passed through
 * verbatim. `null` means the restore would be admissible right now.
 */
internal fun recycleBinRestoreVerdict(row: RecycleBinRow): P705FailureCode? = row.restoreRejectionCode?.takeUnless { it == P705FailureCode.P705_REFUND_LINKED_VOID_NOT_SUPPORTED }

/** Whether the bin offers the restore affordance, using the presentation verdict (never the void-only code). */
internal fun recycleBinRestoreAdmissible(row: RecycleBinRow): Boolean = recycleBinRestoreVerdict(row) == null

/**
 * The restore admissibility line. It renders [recycleBinRestoreVerdict] (never the void-only refund
 * code), so an inadmissible restore is explicit and a void-only code is never shown as a restore
 * verdict (D-158 section 4). The rejection text names the frozen code so an inadmissible restore is
 * never a silently disabled affordance.
 */
internal fun recycleBinRestoreStatusText(row: RecycleBinRow): String {
    val code = recycleBinRestoreVerdict(row)
    return if (code == null) {
        "可恢复"
    } else {
        "不可恢复：${p705FailureCodeLabel(code)}（${code.code}）"
    }
}

/** The empty-bin copy, distinct from a read failure (the R-Q06-4 empty-vs-failure discipline). */
internal const val RECYCLE_BIN_EMPTY_TEXT: String = "回收站为空：没有被作废的交易。"

/** The two typed read-failure copies of the bin (never rendered as an empty bin). */
internal fun recycleBinFailureText(result: RecycleBinResult): String? =
    when (result) {
        is RecycleBinResult.Success -> null
        RecycleBinResult.InvalidState -> "回收站数据不一致，无法展示。"
        RecycleBinResult.Unavailable -> "无法读取回收站（本地数据库不可用）。"
    }

// ------------------------------------------------------------------ form option projections

/**
 * The correction form's category options, derived purely from the authoritative catalog snapshot
 * (the screen never reads the database). The frozen first-slice field set keeps the transaction's
 * kind, so the direction is resolved from the origin's current category: a leaf category whose
 * kind matches the origin category's kind. When the origin category is absent from the catalog
 * (or absent altogether) the union of both ordinary kinds is offered, so a user can still choose —
 * the commit revalidates the reference typed (spec section 3.2).
 */
internal fun correctionCategoryOptions(
    snapshot: CatalogSnapshotView,
    originCategoryId: CategoryId?,
): List<Pair<CategoryId, String>> {
    val direction = snapshot.categories.firstOrNull { it.categoryId == originCategoryId }?.kind
    return snapshot.categories
        .filter { category ->
            category.parentId != null &&
                category.active &&
                (direction == null || category.kind == direction)
        }.map { it.categoryId to it.name }
}

/** The correction form's funding-account options: the active owned real ASSET accounts (spec section 3.2). */
internal fun correctionAccountOptions(snapshot: CatalogSnapshotView): List<Pair<AccountId, String>> =
    snapshot.manageableAccounts
        .filter { it.kind == com.unifiedledger.domain.AccountKind.ASSET && it.active }
        .map { it.accountId to it.name }

/** The catalog projection the correction screens render their current names from. */
internal fun CatalogSnapshotView.correctionCategoryNames(): Map<CategoryId, String> = categories.associate { it.categoryId to it.name }

/** The account-name projection of the same snapshot. */
internal fun CatalogSnapshotView.correctionAccountNames(): Map<AccountId, String> = manageableAccounts.associate { it.accountId to it.name }

// ------------------------------------------------------------------ typed outcome banners

/** P7-05: the typed outcome banner copy of the three surfaces (spec sections 3.2/4.2). */
internal fun p705NoticeText(notice: P705Notice): String =
    when (notice) {
        is P705Notice.Rejected -> "操作被拒绝：${p705FailureCodeLabel(notice.code)}（${notice.code.code}）"
        P705Notice.StaleCurrentVersion -> "该交易已被其他修改更新，请重新打开后再试。"
        is P705Notice.RequestIdentityConflict -> "请求身份冲突：本次请求与该请求编号的既有快照不一致。"
    }

/** The frozen code label of the P7-05 rejection family; the stable `code` token always accompanies it. */
internal fun p705FailureCodeLabel(code: P705FailureCode): String =
    when (code) {
        P705FailureCode.P705_TRANSACTION_NOT_FOUND -> "交易不存在或不在当前账本"
        P705FailureCode.P705_KIND_NOT_SUPPORTED -> "该交易类型暂不支持此操作"
        P705FailureCode.P705_CREATION_LINEAGE_NOT_SUPPORTED -> "导入创建的交易暂不支持此操作"
        P705FailureCode.P705_REFUND_LINKED_VOID_NOT_SUPPORTED -> "该交易存在有效关联退款，暂不可作废"
        P705FailureCode.P705_FIELD_NOT_SUPPORTED -> "请求包含不支持的字段或取值"
        P705FailureCode.P705_STALE_CURRENT_VERSION -> "交易版本已变更"
        P705FailureCode.P705_TRANSACTION_VOIDED -> "该交易已被作废"
        P705FailureCode.P705_TRANSACTION_NOT_VOIDED -> "该交易未被作废"
        P705FailureCode.P705_VOID_CYCLE_EXHAUSTED -> "该交易已作废并已恢复，不能再次作废"
        P705FailureCode.P705_VOID_REASON_REQUIRED -> "必须选择原因，且说明不超过上限"
        P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE -> "引用的账户或分类当前不可用"
        P705FailureCode.P705_MATCHED_FUNDING_LEG_CHANGED -> "受影响资金腿的失效组合未获授权"
        P705FailureCode.P705_NO_CHANGE -> "请求与当前状态完全一致"
        P705FailureCode.P705_REQUEST_IDENTITY_CONFLICT -> "同一请求编号的快照不一致"
        P705FailureCode.P705_CONSTRAINT_VIOLATION -> "写入被约束拒绝，操作已回滚"
    }
