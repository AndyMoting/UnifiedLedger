package com.unifiedledger.ui

import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportDuplicateCandidateKind
import com.unifiedledger.application.ImportDuplicateReviewRow
import com.unifiedledger.application.ImportDuplicateReviewsResult
import com.unifiedledger.application.ImportDuplicateStatus
import com.unifiedledger.application.ImportFileIntakeOutcome
import com.unifiedledger.application.ImportFormatAvailability
import com.unifiedledger.application.ImportFormatCapabilities
import com.unifiedledger.application.ImportFormatDescriptor
import com.unifiedledger.application.ImportFundingState
import com.unifiedledger.application.ImportIntakeBatchFailure
import com.unifiedledger.application.ImportIntakeRecordDisposition
import com.unifiedledger.application.ImportIntakeRecordSummary
import com.unifiedledger.application.ImportPlatformKind
import com.unifiedledger.application.ImportReviewRow
import com.unifiedledger.application.ManualExpenseAmountFormatError
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.domain.CurrencyUnit

// P7-04.C pure presentation decisions (D-146; spec sections 3.3.1/4.5/4.6/6.4, P704SPEC-12).
// The composables in P503ImportReview.kt stay thin renderers; every load-bearing decision —
// the six-class candidate classification (with the incomplete-first red line), the selection
// gate (先审后勾), the missing-source-fact explanation, the format matrix entries, the session
// summary copy, the decision form face and its validation, and the batch-disposition group
// boundary — is a pure function here, assertable in the app-ui JVM tests without a Compose
// UI-test harness. No Compose or platform API is used in this file.

/**
 * The six presentation classes of the candidate classification matrix (spec section 3.3.1; a pure
 * projection over the persisted fields, zero writes). The classification keys are
 * candidateStatus/candidateKind/completeness/fundingState/requiresConfirmation/duplicateStatus:
 * candidateStatus decides the class boundaries, kind/completeness/fundingState drive the
 * INCOMPLETE explanation (R-Q10-3), and duplicateStatus drives the review/block/retain branches.
 */
internal enum class ImportCandidateClass {
    /** 待确认——缺用户决策（可勾选，须先补决策字段）。 */
    PENDING_USER_DECISION,

    /** 疑似重复——待审核（不可勾选，R-Q10-1 UI 门；呈现比较快照与审核动作）。 */
    SUSPECTED_DUPLICATE_PENDING_REVIEW,

    /** 重复已确认——阻断（不可勾选，core 既有门，D-104/D-105）。 */
    DUPLICATE_CONFIRMED_BLOCKED,

    /** 可保留的相似记录（CONFIRMED_DISTINCT/DISMISSED_LOOKALIKE 且 pending；可勾选）。 */
    RETAINABLE_SIMILAR,

    /** 来源事实不完整——不可确认（R-Q10-3；呈现缺什么来源事实，无编辑入口）。 */
    INCOMPLETE_SOURCE_FACTS,

    /** 已确认/已拒绝（历史呈现）。 */
    RESOLVED,
}

/** The selection gate of the classification matrix: only the two user-decidable classes may be checked. */
internal val ImportCandidateClass.selectable: Boolean
    get() = this == ImportCandidateClass.PENDING_USER_DECISION || this == ImportCandidateClass.RETAINABLE_SIMILAR

/**
 * Classifies one review row (spec section 3.3.1 matrix, pure projection). RED LINE (评审裁决):
 * `candidateStatus == "incomplete"` is decided FIRST — a NO_FUNDS candidate carries a folded
 * `DEFERRED` duplicate status (from its CLOSED_OR_FAILED_NO_FUNDS group) and must never be
 * presented as 疑似重复待审核; it is 来源事实不完整 — not selectable, with an explanation and no
 * edit entry. A pending candidate's folded duplicate status then decides the review/block/retain
 * branch; the core-block gate stays `CONFIRMED_DUPLICATE` only (P4-07 narrow formalization gate).
 */
internal fun classifyImportCandidate(row: ImportReviewRow): ImportCandidateClass =
    when (row.candidateStatus) {
        // Red line: incomplete is checked before any duplicate branch (R-Q10-3).
        "incomplete" -> ImportCandidateClass.INCOMPLETE_SOURCE_FACTS
        "confirmed", "rejected" -> ImportCandidateClass.RESOLVED
        "pending_confirmation" ->
            when (row.duplicateStatus) {
                ImportDuplicateStatus.CONFIRMED_DUPLICATE -> ImportCandidateClass.DUPLICATE_CONFIRMED_BLOCKED
                ImportDuplicateStatus.DEFERRED -> ImportCandidateClass.SUSPECTED_DUPLICATE_PENDING_REVIEW
                ImportDuplicateStatus.CONFIRMED_DISTINCT,
                ImportDuplicateStatus.DISMISSED_LOOKALIKE,
                -> ImportCandidateClass.RETAINABLE_SIMILAR
                // No duplicate candidate, or a folded terminal review verdict the UI never issues
                // (REJECTED 保留 core 值域、UI 不可达): nothing unreviewed remains, and the core
                // blocks confirmation on CONFIRMED_DUPLICATE only, so the candidate stays the
                // ordinary user-decision class.
                null,
                ImportDuplicateStatus.REJECTED,
                -> ImportCandidateClass.PENDING_USER_DECISION
            }
        else -> throw IllegalStateException("unknown import candidate status token: ${row.candidateStatus}")
    }

/**
 * The frozen display order of the classification groups (a presentation choice, registered; the
 * class boundaries themselves are the frozen matrix above).
 */
internal val IMPORT_CANDIDATE_CLASS_DISPLAY_ORDER: List<ImportCandidateClass> =
    listOf(
        ImportCandidateClass.PENDING_USER_DECISION,
        ImportCandidateClass.SUSPECTED_DUPLICATE_PENDING_REVIEW,
        ImportCandidateClass.RETAINABLE_SIMILAR,
        ImportCandidateClass.DUPLICATE_CONFIRMED_BLOCKED,
        ImportCandidateClass.INCOMPLETE_SOURCE_FACTS,
        ImportCandidateClass.RESOLVED,
    )

/** One classification group of the IMPORT candidate list. */
internal data class ImportCandidateClassGroup(
    val classToken: ImportCandidateClass,
    val rows: List<ImportReviewRow>,
)

/** Groups the rows by the classification matrix in the frozen display order (empty groups omitted). */
internal fun importCandidateClassGroups(rows: List<ImportReviewRow>): List<ImportCandidateClassGroup> =
    IMPORT_CANDIDATE_CLASS_DISPLAY_ORDER.mapNotNull { classToken ->
        val classRows = rows.filter { classifyImportCandidate(it) == classToken }
        if (classRows.isEmpty()) null else ImportCandidateClassGroup(classToken, classRows)
    }

/** The header copy of one classification group. */
internal fun importCandidateClassLabel(classToken: ImportCandidateClass): String =
    when (classToken) {
        ImportCandidateClass.PENDING_USER_DECISION -> "待确认——缺用户决策"
        ImportCandidateClass.SUSPECTED_DUPLICATE_PENDING_REVIEW -> "疑似重复——待审核"
        ImportCandidateClass.DUPLICATE_CONFIRMED_BLOCKED -> "重复已确认——阻断"
        ImportCandidateClass.RETAINABLE_SIMILAR -> "可保留的相似记录"
        ImportCandidateClass.INCOMPLETE_SOURCE_FACTS -> "来源事实不完整——不可确认"
        ImportCandidateClass.RESOLVED -> "已确认 / 已拒绝"
    }

/**
 * R-Q10-3: what source fact is missing, derived from candidate_kind + completeness + funding_state
 * (spec section 3.3.1 来源事实不完整 row). Presentation copy only; the candidate facts themselves
 * are never editable and the row never offers an edit entry.
 */
internal fun importIncompleteSourceFactsExplanation(row: ImportReviewRow): String =
    when {
        row.fundingState == ImportFundingState.NO_FUNDS ->
            "来源事实不完整：交易已关闭或失败，且没有可入账的资金变化（NO_FUNDS），不可确认。"
        row.candidateKind == "transfer_flow_missing_leg" ->
            "来源事实不完整：转账记录缺少对方腿（转出/转入信息不全），不可确认。"
        row.fundingState == ImportFundingState.UNRESOLVED ->
            "来源事实不完整：来源交易状态未解，不可确认。"
        else -> "来源事实不完整：缺少必要的事实字段，不可确认。"
    }

/** The amount line of one candidate row (exact minor units; sign and currency preserved, spec 6.4). */
internal fun importCandidateAmountText(row: ImportReviewRow): String {
    val amountMinor = row.amountMinor
    val currencyCode = row.currencyCode
    return if (amountMinor != null && currencyCode != null) {
        val precision = row.currencyPrecision ?: 0
        "${formatMinorUnits(amountMinor, precision)} $currencyCode"
    } else {
        "金额未解"
    }
}

/** The metadata line of one candidate row: kind, occurrence time, direction/status tokens. */
internal fun importCandidateMetaText(row: ImportReviewRow): String {
    val parts = mutableListOf("类型 ${row.candidateKind}")
    row.occurredAt?.let { parts += "发生 $it" }
    row.directionToken?.let { parts += "方向 $it" }
    row.statusToken?.let { parts += "状态 $it" }
    parts += "重复 ${row.duplicateStatus?.name ?: "无"}"
    return parts.joinToString("；")
}

// ------------------------------------------------------------------ format capability matrix

/** One rendered import entry of the capability matrix (spec sections 3.1.1/4.2.1). */
internal data class ImportFormatEntry(
    val descriptor: ImportFormatDescriptor,
    val availability: ImportFormatAvailability,
)

/**
 * The import entries the UI renders for a platform: every matrix format appears (不静默省略),
 * each with its declared availability on that platform — the CCB XLS entry on Android carries
 * PENDING_DEVICE_VERIFICATION and is never presented as 可用 (R-Q08-3); picking it still runs the
 * pipeline so the intake (a) gate produces the typed 该格式在 Android 待运行验证 result.
 */
internal fun importFormatEntries(platform: ImportPlatformKind): List<ImportFormatEntry> = ImportFormatCapabilities.ALL.map { ImportFormatEntry(descriptor = it, availability = it.availabilityOn(platform)) }

// ------------------------------------------------------------------ intake session summary / failures

/**
 * The copy lines of the most recent session summary（显示名 + 逐项结果计数）, including the typed
 * failure text when the pipeline failed (spec sections 6.1/4.6). The display name is
 * session-only (D06); nothing here leaks raw rows, paths, or personal identifiers.
 */
internal fun importIntakeSessionLines(session: ImportIntakeSessionSummary): List<String> {
    val lines = mutableListOf<String>()
    lines += "最近导入：${session.displayName.ifBlank { "（未提供文件名）" }}"
    when (val outcome = session.outcome) {
        is ImportIntakePipelineOutcome.Intaken ->
            when (val intaken = outcome.outcome) {
                is com.unifiedledger.application.ImportFileIntakeOutcome.Accepted -> {
                    val counts = importIntakeDispositionCounts(intaken.records)
                    lines +=
                        "接治完成：新增 ${counts[ImportIntakeRecordDisposition.INTAKE_ACCEPTED] ?: 0}" +
                        "，等价重放 ${counts[ImportIntakeRecordDisposition.INTAKE_NO_CHANGE] ?: 0}" +
                        "，解析拒绝 ${counts[ImportIntakeRecordDisposition.PARSER_REJECTED] ?: 0}" +
                        "，接治拒绝 ${counts[ImportIntakeRecordDisposition.INTAKE_REJECTED] ?: 0}。"
                }
                is com.unifiedledger.application.ImportFileIntakeOutcome.NoChangeAll -> {
                    val counts = importIntakeDispositionCounts(intaken.records)
                    lines +=
                        "全部记录为等价重放，零新写入（共 ${counts[ImportIntakeRecordDisposition.INTAKE_NO_CHANGE] ?: 0} 条）。"
                }
                is com.unifiedledger.application.ImportFileIntakeOutcome.Rejected -> lines += importIntakeFailureText(intaken.failure)
            }
        is ImportIntakePipelineOutcome.ReadExceedsLimit ->
            lines += "文件超过读取上限（16 MiB），实际 ${outcome.actualBytes} 字节；本次未解析、未接治。"
        is ImportIntakePipelineOutcome.ReadFailed -> lines += importPickReadFailureText(outcome.reason)
    }
    return lines
}

/** The per-record result lines (逐项结果), disclosed-capped for rendering (a display choice, registered). */
internal fun importIntakeRecordLines(
    records: List<ImportIntakeRecordSummary>,
    displayLimit: Int = 200,
): List<String> {
    val rendered =
        records.take(displayLimit).map { record ->
            when (record.disposition) {
                ImportIntakeRecordDisposition.INTAKE_ACCEPTED -> "第 ${record.recordOrdinal} 条：已接治（新增候选）。"
                ImportIntakeRecordDisposition.INTAKE_NO_CHANGE -> "第 ${record.recordOrdinal} 条：等价重放，零新写入。"
                ImportIntakeRecordDisposition.PARSER_REJECTED -> "第 ${record.recordOrdinal} 条：单行无法解析（诊断码 ${record.diagnosticCode.orEmpty()}）。"
                ImportIntakeRecordDisposition.INTAKE_REJECTED -> "第 ${record.recordOrdinal} 条：接治拒绝（诊断码 ${record.diagnosticCode.orEmpty()}）。"
            }
        }
    val remaining = records.size - rendered.size
    return if (remaining > 0) rendered + "其余 $remaining 条见上方计数。" else rendered
}

/** The typed batch-failure copy (spec section 4.6 failure family; code tokens only, D06). */
internal fun importIntakeFailureText(failure: ImportIntakeBatchFailure): String =
    when (failure) {
        is ImportIntakeBatchFailure.FormatUnavailable ->
            when (failure.reason) {
                com.unifiedledger.application.ImportFormatUnavailableReason.PENDING_DEVICE_VERIFICATION ->
                    "该格式在 ${failure.formatDisplayName} 当前平台待运行验证，本次未解析、未接治。"
                com.unifiedledger.application.ImportFormatUnavailableReason.CHARSET_UNSUPPORTED ->
                    "运行环境缺少 ${failure.formatDisplayName} 所需字符集（GB18030），本次未解析、未接治。"
            }
        is ImportIntakeBatchFailure.BatchExceedsLimit ->
            "单次导入最多 10,000 条已接受记录，本次 ${failure.actualAcceptedRecords} 条；已中止，未写入任何记录。"
        is ImportIntakeBatchFailure.ParserRejected ->
            "文件解析失败（诊断码 ${failure.diagnostic.code}），本次导入已中止。"
    }

/** The typed L0 stream-failure copy (no raw exception text, path, or URI; D06). */
internal fun importPickReadFailureText(reason: ImportPickReadFailure): String =
    when (reason) {
        ImportPickReadFailure.STREAM_OPEN_FAILED -> "文件读取失败（无法打开文件流，可能权限已撤销）。"
        ImportPickReadFailure.STREAM_READ_FAILED -> "文件读取失败（读取中途失败）。"
    }

/** The banner copy of one IMPORT overview notice (spec 6.2 读失败不篡改 + section 4.6). */
internal fun importReviewNoticeText(notice: ImportReviewNotice): String =
    when (notice) {
        ImportReviewNotice.ReviewReadFailed ->
            "导入清单读取失败；以下保留上一次成功加载的清单，可重新刷新。"
        is ImportReviewNotice.IntakeFailed ->
            when (val outcome = notice.outcome) {
                is ImportIntakePipelineOutcome.Intaken ->
                    (outcome.outcome as? com.unifiedledger.application.ImportFileIntakeOutcome.Rejected)
                        ?.let { importIntakeFailureText(it.failure) }
                        ?: "本次导入失败；未写入任何记录。"
                is ImportIntakePipelineOutcome.ReadExceedsLimit ->
                    "文件超过读取上限（16 MiB），实际 ${outcome.actualBytes} 字节；本次未解析、未接治。"
                is ImportIntakePipelineOutcome.ReadFailed -> importPickReadFailureText(outcome.reason)
            }
        is ImportReviewNotice.ReviewRejected ->
            "重复审核未通过（诊断码 ${notice.code}），未写入任何记录。"
        // P704D-SPEC-02: an infrastructure failure is NOT a verdict — the copy never claims
        // 审核未通过； the core never returned, zero writes happened and the action is retryable.
        is ImportReviewNotice.ReviewSubmitFailed ->
            "重复审核提交失败（原因码 ${notice.code}），未写入任何记录；可重新提交。"
    }

private fun importIntakeDispositionCounts(records: List<ImportIntakeRecordSummary>): Map<ImportIntakeRecordDisposition, Int> = records.groupingBy { it.disposition }.eachCount()

// ------------------------------------------------------------------ duplicate review target / group boundary

/**
 * The single review target of the detail page: the first comparison row that is both
 * `EXACT_BUSINESS_TUPLE` and latest `DEFERRED` (multi-unreviewed rows are dispositioned one at a
 * time; the UI presents the first unreviewed row's actions). A CLOSED_OR_FAILED_NO_FUNDS row is
 * never a review target: the core rejects CONFIRMED_DUPLICATE on it with decisionKindMismatch
 * and its subject candidate belongs to 来源事实不完整 (D-105).
 */
internal fun importDuplicateReviewTarget(duplicates: ImportDuplicateReviewsResult): ImportDuplicateReviewRow? =
    (duplicates as? ImportDuplicateReviewsResult.Reviews)
        ?.reviews
        ?.firstOrNull {
            it.kind == ImportDuplicateCandidateKind.EXACT_BUSINESS_TUPLE.name && it.latestStatus == ImportDuplicateStatus.DEFERRED
        }

/**
 * P704SPEC-12 group boundary (frozen): a duplicate review row joins the batch disposition group
 * iff its kind is `EXACT_BUSINESS_TUPLE`, its subject source's `input_ref` equals the current
 * pick session handle, and its latest duplicate status is `DEFERRED`. The comparison fingerprint
 * is never a grouping key (its input contains subject_source_id, unique per candidate); the
 * comparison snapshot is the 整组确认页's per-item presentation duty, not a query condition.
 */
internal fun isImportDuplicateBatchGroupMember(
    sessionInputRef: String,
    subjectInputRef: String,
    reviewRow: ImportDuplicateReviewRow,
): Boolean =
    subjectInputRef == sessionInputRef &&
        reviewRow.kind == ImportDuplicateCandidateKind.EXACT_BUSINESS_TUPLE.name &&
        reviewRow.latestStatus == ImportDuplicateStatus.DEFERRED

/**
 * The row-level view of the batch disposition group: the current session's suspected-duplicate
 * candidates (the classification matrix + the subject handle). Under the spine's branch
 * invariants a pending candidate's folded `DEFERRED` status can only come from an
 * `EXACT_BUSINESS_TUPLE` duplicate (NO_FUNDS subjects are `incomplete` and never reach here),
 * so this row-level group equals the P704SPEC-12 duplicate-row group's subjects.
 */
internal fun importDuplicateGroupRows(
    rows: List<ImportReviewRow>,
    sessionInputRef: String,
): List<ImportReviewRow> =
    rows.filter {
        classifyImportCandidate(it) == ImportCandidateClass.SUSPECTED_DUPLICATE_PENDING_REVIEW &&
            it.sourceInputRef == sessionInputRef
    }

/** The typed outcome of one 整组确认页 enumeration (P704C-SPEC-05; never a silent partial group). */
internal sealed interface ImportDuplicateGroupEnumeration {
    /** Every group member with its privacy-safe comparison snapshot, in row order. */
    data class Ready(
        val items: List<ImportDuplicateGroupDispositionItem>,
    ) : ImportDuplicateGroupEnumeration

    /**
     * At least one per-candidate duplicate-review read failed typed: the enumeration aborts
     * wholesale — a partial group must never be presented; the host surfaces the typed
     * list-failure banner instead (F1/G6).
     */
    data object ReadFailed : ImportDuplicateGroupEnumeration
}

/**
 * P704C-SPEC-05: enumerates the current pick session's suspected-duplicate group as a testable
 * pure function (the @Composable host only wires the read callback). Per row-level group member
 * every comparison row that satisfies the P704SPEC-12 boundary becomes one item; a
 * [ImportDuplicateReviewsResult.Absent]/[NoDuplicates] verdict contributes nothing without
 * failing; the FIRST [ImportDuplicateReviewsResult.Unavailable] aborts the whole enumeration
 * ([ImportDuplicateGroupEnumeration.ReadFailed]) — never a silently partial group.
 */
internal fun enumerateImportDuplicateGroupItems(
    sessionInputRef: String,
    rows: List<ImportReviewRow>,
    loadReviews: (com.unifiedledger.application.ImportCandidateId) -> ImportDuplicateReviewsResult,
): ImportDuplicateGroupEnumeration {
    val items = mutableListOf<ImportDuplicateGroupDispositionItem>()
    for (row in importDuplicateGroupRows(rows, sessionInputRef)) {
        when (val reviews = loadReviews(row.candidateId)) {
            is ImportDuplicateReviewsResult.Reviews ->
                reviews.reviews
                    .filter { isImportDuplicateBatchGroupMember(sessionInputRef, row.sourceInputRef, it) }
                    .forEach { reviewRow ->
                        items +=
                            ImportDuplicateGroupDispositionItem(
                                candidateId = row.candidateId,
                                duplicateCandidateId = reviewRow.duplicateCandidateId,
                                comparisonSnapshot = reviewRow.comparisonSnapshot,
                                expectedComparisonFingerprint = reviewRow.comparisonFingerprint,
                            )
                    }
            ImportDuplicateReviewsResult.NoDuplicates,
            ImportDuplicateReviewsResult.Absent,
            -> Unit
            ImportDuplicateReviewsResult.Unavailable -> return ImportDuplicateGroupEnumeration.ReadFailed
        }
    }
    return ImportDuplicateGroupEnumeration.Ready(items)
}

/**
 * P704C-SPEC-04 (spec section 4.5.4): the possibly-existing source facts of one comparison row —
 * amount/time/direction/status, privacy-safe, exact minor units — or the explicit no-target copy
 * for a CLOSED_OR_FAILED_NO_FUNDS row (D-105: no directed target).
 */
internal fun importPossibleExistingSourceText(
    facts: com.unifiedledger.application.ImportDuplicatePossibleExistingSourceFacts?,
): String {
    if (facts == null) return "无既有来源事实（该重复候选无指向目标）。"
    val amountMinor = facts.amountMinor
    val amount =
        if (amountMinor != null) {
            "${formatMinorUnits(amountMinor, facts.currencyPrecision ?: 0)} ${facts.currencyCode.orEmpty()}"
        } else {
            "金额未解"
        }
    val parts = mutableListOf("既有来源：$amount")
    facts.occurredAt?.let { parts += "发生 $it" }
    facts.directionToken?.let { parts += "方向 $it" }
    facts.statusToken?.let { parts += "状态 $it" }
    return parts.joinToString("；")
}

// ------------------------------------------------------------------ decision form face + validation

/**
 * The decision form face of one candidate kind (spec section 4.5.3): the six
 * `ImportConfirmDecisionFields` variants mapped one-to-one, zero new semantics. The refund
 * variant of a credit expense is distinguished by the persisted payment profile variant
 * (`credit_expense` kind + `credit_expense_refund` profile), exactly like the core's confirm
 * kind gate. `null` for kinds without a decision form (missing-leg transfers and every
 * non-selectable shape).
 */
internal data class ImportDecisionFormFace(
    val requiresCategory: Boolean,
    val requiresFundingAccount: Boolean,
    val requiresFromAccount: Boolean,
    val requiresToAccount: Boolean,
    val requiresCreditAccount: Boolean,
    val requiresOriginalTransaction: Boolean,
    val requiresAssetAccount: Boolean,
    /** Mixed payment: the two leg amounts are editable and nullable (incomplete decision data stays pending). */
    val legAmountsEditable: Boolean,
    /** Mixed payment E13 gate: the confirmation time is required (missing = 不可提交). */
    val requiresConfirmedAt: Boolean,
)

internal fun importDecisionFormFace(row: ImportReviewRow): ImportDecisionFormFace? =
    when (row.candidateKind) {
        "ordinary_flow" ->
            ImportDecisionFormFace(
                requiresCategory = true,
                requiresFundingAccount = true,
                requiresFromAccount = false,
                requiresToAccount = false,
                requiresCreditAccount = false,
                requiresOriginalTransaction = false,
                requiresAssetAccount = false,
                legAmountsEditable = false,
                requiresConfirmedAt = false,
            )
        "transfer_flow" ->
            ImportDecisionFormFace(
                requiresCategory = false,
                requiresFundingAccount = false,
                requiresFromAccount = true,
                requiresToAccount = true,
                requiresCreditAccount = false,
                requiresOriginalTransaction = false,
                requiresAssetAccount = false,
                legAmountsEditable = false,
                requiresConfirmedAt = false,
            )
        "credit_expense" ->
            if (row.paymentProfileVariant == "credit_expense_refund") {
                ImportDecisionFormFace(
                    requiresCategory = true,
                    requiresFundingAccount = false,
                    requiresFromAccount = false,
                    requiresToAccount = false,
                    requiresCreditAccount = true,
                    requiresOriginalTransaction = true,
                    requiresAssetAccount = false,
                    legAmountsEditable = false,
                    requiresConfirmedAt = false,
                )
            } else {
                ImportDecisionFormFace(
                    requiresCategory = true,
                    requiresFundingAccount = false,
                    requiresFromAccount = false,
                    requiresToAccount = false,
                    requiresCreditAccount = true,
                    requiresOriginalTransaction = false,
                    requiresAssetAccount = false,
                    legAmountsEditable = false,
                    requiresConfirmedAt = false,
                )
            }
        "credit_repayment" ->
            ImportDecisionFormFace(
                requiresCategory = false,
                requiresFundingAccount = false,
                requiresFromAccount = false,
                requiresToAccount = false,
                requiresCreditAccount = true,
                requiresOriginalTransaction = false,
                requiresAssetAccount = true,
                legAmountsEditable = false,
                requiresConfirmedAt = false,
            )
        "mixed_payment" ->
            ImportDecisionFormFace(
                requiresCategory = true,
                requiresFundingAccount = false,
                requiresFromAccount = false,
                requiresToAccount = false,
                requiresCreditAccount = true,
                requiresOriginalTransaction = false,
                requiresAssetAccount = true,
                legAmountsEditable = true,
                requiresConfirmedAt = true,
            )
        else -> null
    }

/**
 * The one-shot decision-form errors (the [P503DraftValidation] pattern: evaluated once, read per
 * field). Missing booleans mirror the form face; the mixed leg amounts are OPTIONAL (a blank is
 * valid null decision data — the candidate stays pending) but a non-blank text must parse
 * exactly at the currency precision; the mixed confirmation time is required (E13).
 */
internal data class ImportDecisionErrors(
    val missingCategory: Boolean,
    val missingFundingAccount: Boolean,
    val missingFromAccount: Boolean,
    val missingToAccount: Boolean,
    val missingCreditAccount: Boolean,
    val missingOriginalTransaction: Boolean,
    val missingAssetAccount: Boolean,
    val assetLegAmountError: ManualExpenseAmountFormatError?,
    val creditLegAmountError: ManualExpenseAmountFormatError?,
    val missingConfirmedAt: Boolean,
) {
    val hasErrors: Boolean
        get() =
            missingCategory ||
                missingFundingAccount ||
                missingFromAccount ||
                missingToAccount ||
                missingCreditAccount ||
                missingOriginalTransaction ||
                missingAssetAccount ||
                assetLegAmountError != null ||
                creditLegAmountError != null ||
                missingConfirmedAt
}

/** The pure decision-form validation shared by the detail screen and the D-batch submit gate. */
internal class P503ImportDecisionValidation(
    private val parseAmount: ParseManualExpenseAmount,
) {
    fun errors(
        draft: ImportDecisionDraft,
        face: ImportDecisionFormFace,
        currency: CurrencyUnit,
    ): ImportDecisionErrors =
        ImportDecisionErrors(
            missingCategory = face.requiresCategory && draft.categoryId == null,
            missingFundingAccount = face.requiresFundingAccount && draft.fundingAccountId == null,
            missingFromAccount = face.requiresFromAccount && draft.fromAccountId == null,
            missingToAccount = face.requiresToAccount && draft.toAccountId == null,
            missingCreditAccount = face.requiresCreditAccount && draft.creditLiabilityAccountId == null,
            missingOriginalTransaction = face.requiresOriginalTransaction && draft.originalTransactionId == null,
            missingAssetAccount = face.requiresAssetAccount && draft.assetAccountId == null,
            assetLegAmountError = if (face.legAmountsEditable) draft.assetLegAmountText.amountError(currency) else null,
            creditLegAmountError = if (face.legAmountsEditable) draft.creditLegAmountText.amountError(currency) else null,
            missingConfirmedAt = face.requiresConfirmedAt && draft.confirmedAtText.isBlank(),
        )

    /** Mixed 确认时间缺失 = 不可提交 (E13); every other required field must be present too. */
    fun isSubmittable(
        draft: ImportDecisionDraft,
        face: ImportDecisionFormFace,
        currency: CurrencyUnit,
    ): Boolean = !errors(draft, face, currency).hasErrors

    private fun String.amountError(currency: CurrencyUnit): ManualExpenseAmountFormatError? =
        if (isBlank()) {
            null
        } else {
            when (val parsed = parseAmount.parse(this, currency)) {
                is ParseManualExpenseAmount.Result.Valid -> null
                is ParseManualExpenseAmount.Result.Invalid -> parsed.error
            }
        }
}

// ------------------------------------------------------------------ flattened disposition-card copy (Option A)

/** The title copy of the open 整组确认页 card header (frozen verbatim from the pre-windowing card). */
internal fun groupDispositionCardHeaderTitle(): String = "整组标记为重复（逐条核对）"

/** The disclosure copy of the open card header: the per-item manual-review semantics + the item count. */
internal fun groupDispositionCardHeaderDisclosure(itemCount: Int): String = "将逐条提交人工审核判定为重复，每条独立生效；某一条失败不影响其余各条。共 $itemCount 条。"

/** The session-handle copy of the open card header (the opaque pick session handle, R-Q09-1). */
internal fun groupDispositionCardHeaderSessionText(inputRef: String): String = "本次会话：$inputRef"

/** The confirm-button copy of the open card footer (frozen verbatim from the pre-windowing card). */
internal fun groupDispositionCardFooterConfirmText(): String = "确认整组标记"

/** The close-button copy of the open card footer (frozen verbatim from the pre-windowing card). */
internal fun groupDispositionCardFooterCloseText(): String = "关闭"

/** The subject-candidate copy of one flattened card item. */
internal fun groupDispositionItemCandidateText(state: ImportDuplicateGroupItemState): String = "候选 ${state.item.candidateId.value}"

/** The privacy-safe comparison-snapshot copy of one flattened card item. */
internal fun groupDispositionItemComparisonText(state: ImportDuplicateGroupItemState): String = "比较信息：${state.item.comparisonSnapshot}"

/** The per-item outcome copy of one flattened card item (待处置 / 已标记 / 失败, frozen verbatim). */
internal fun groupDispositionItemOutcomeText(state: ImportDuplicateGroupItemState): String =
    when (val outcome = state.outcome) {
        null -> "待处置"
        is ImportDuplicateGroupItemResult.Reviewed -> "已标记：${outcome.outcome.name}"
        is ImportDuplicateGroupItemResult.Rejected -> "失败（${outcome.code}）"
    }

// ------------------------------------------------------------------ overview render model (FOUND-P704-D01-01)

/**
 * The flat, ordered render model of the IMPORT overview (spec sections 6.1/6.4), consumed by the
 * LazyColumn in P503ImportScreen. FOUND-P704-D01-01 showed that eagerly composing every candidate
 * row inside a scrolling Column exhausts memory fatally at the registered intake cap (10,000
 * candidates, four device reproductions), so the overview renders a lazy window over this list
 * instead. Every flattened section — the title bar, the notice banner, the dividers, the format
 * entries, the session summary (header lines + the disclosed 200-record per-record lines), the
 * 待确认 header with its two empty states, the group headers, the candidate rows, the disposition
 * affordances, the open disposition card (expanded as one header + one item per group member + one
 * footer), the batch-confirm entry and the per-item batch result expansion — is one item
 * carrying a unique [stableKey] and a [contentType] reuse token. Pure projection only: no Compose
 * API and no callbacks (the composable wires the typed intents). No new display limit is
 * introduced: the candidate list itself is never truncated; the 200-record session披露 cap stays
 * exactly the registered display choice of [importIntakeRecordLines], reused verbatim.
 */
internal sealed interface ImportReviewRenderItem {
    /** The LazyColumn item key: deterministic for the same input, unique across the whole list. */
    val stableKey: String

    /** The LazyColumn contentType reuse token: one per rendered shape. */
    val contentType: String

    /** 标题栏（「导入」+ 刷新清单）。 */
    data object TitleBar : ImportReviewRenderItem {
        override val stableKey: String = "title-bar"
        override val contentType: String = "titleBar"
    }

    /** The typed failure banner (notice 有/无; spec 6.2 读失败不篡改). */
    data class NoticeBanner(
        val notice: ImportReviewNotice,
    ) : ImportReviewRenderItem {
        override val stableKey: String = "notice-banner"
        override val contentType: String = "notice"
    }

    /** One visual divider band (HorizontalDivider with its surrounding spacing). */
    data class SectionDivider(
        val slot: ImportReviewDividerSlot,
    ) : ImportReviewRenderItem {
        override val stableKey: String = "divider:${slot.name}"
        override val contentType: String = "divider"
    }

    /** 「选择账单文件」 section header. */
    data object FormatSectionHeader : ImportReviewRenderItem {
        override val stableKey: String = "format-header"
        override val contentType: String = "sectionHeader"
    }

    /** One capability-matrix format entry (name, honest availability, pick button). */
    data class FormatEntry(
        val entry: ImportFormatEntry,
    ) : ImportReviewRenderItem {
        override val stableKey: String = "format:${entry.descriptor.identifier.value}"
        override val contentType: String = "formatEntry"
    }

    /** 「最近导入」 section header (spec section 6.1). */
    data object IntakeSessionHeader : ImportReviewRenderItem {
        override val stableKey: String = "intake-header"
        override val contentType: String = "sectionHeader"
    }

    /** One session-summary copy line ([importIntakeSessionLines], verbatim). */
    data class IntakeSessionLine(
        val ordinal: Int,
        val line: String,
    ) : ImportReviewRenderItem {
        override val stableKey: String = "intake-summary:$ordinal"
        override val contentType: String = "intakeSessionLine"
    }

    /**
     * One per-record intake line ([importIntakeRecordLines], verbatim — including the trailing
     * 「其余 $remaining 条见上方计数。」 disclosure line of the registered 200-record display cap).
     */
    data class IntakeRecordLine(
        val ordinal: Int,
        val line: String,
    ) : ImportReviewRenderItem {
        override val stableKey: String = "intake-record:$ordinal"
        override val contentType: String = "intakeRecordLine"
    }

    /** 「待确认草稿」 section header. */
    data object PendingSectionHeader : ImportReviewRenderItem {
        override val stableKey: String = "pending-header"
        override val contentType: String = "sectionHeader"
    }

    /** 导入清单尚未加载。 (projection not loaded yet). */
    data object UnloadedEmptyState : ImportReviewRenderItem {
        override val stableKey: String = "empty-unloaded"
        override val contentType: String = "emptyState"
    }

    /** 暂无导入候选。 (loaded, no active notice, zero rows). */
    data object NoCandidatesEmptyState : ImportReviewRenderItem {
        override val stableKey: String = "empty-no-candidates"
        override val contentType: String = "emptyState"
    }

    /** The 「标签（N）」 header of one classification group (frozen order, empty groups omitted). */
    data class GroupHeader(
        val classToken: ImportCandidateClass,
        val rowCount: Int,
    ) : ImportReviewRenderItem {
        override val stableKey: String = "group:${classToken.name}"
        override val contentType: String = "groupHeader"
    }

    /** One candidate row with its selection state (selected = 勾选集 membership). */
    data class CandidateItem(
        val row: ImportReviewRow,
        val selected: Boolean,
    ) : ImportReviewRenderItem {
        override val stableKey: String = "candidate:${row.candidateId.value}"
        override val contentType: String = "candidate"
    }

    /** 「整组标记为重复」 affordance (only while the current pick session has a P704SPEC-12 group). */
    data object GroupDispositionButton : ImportReviewRenderItem {
        override val stableKey: String = "group-disposition-button"
        override val contentType: String = "groupDispositionButton"
    }

    /**
     * The header of the open 整组确认页 card (Option A windowing): the group's session handle and
     * its item count. The open card expands flat as one header + one [GroupDispositionItem] per
     * group member + one [GroupDispositionCardFooter], so the LazyColumn windows the card's items
     * exactly like the candidate list (no eager per-item composition inside a single card item).
     */
    data class GroupDispositionCardHeader(
        val inputRef: String,
        val itemCount: Int,
    ) : ImportReviewRenderItem {
        override val stableKey: String = "group-disposition-card:header"
        override val contentType: String = "groupDispositionCardHeader"
    }

    /**
     * One member of the open 整组确认页 card, carrying the whole [ImportDuplicateGroupItemState]
     * (the frozen comparison snapshot + the per-item outcome). The key is the duplicate candidate
     * id — the same subject candidate can contribute multiple review rows to the group.
     */
    data class GroupDispositionItem(
        val state: ImportDuplicateGroupItemState,
    ) : ImportReviewRenderItem {
        override val stableKey: String = "group-disposition-item:${state.item.duplicateCandidateId.value}"
        override val contentType: String = "groupDispositionItem"
    }

    /** The footer of the open 整组确认页 card: the 确认整组标记 / 关闭 affordance row. */
    data class GroupDispositionCardFooter(
        val itemCount: Int,
    ) : ImportReviewRenderItem {
        override val stableKey: String = "group-disposition-card:footer"
        override val contentType: String = "groupDispositionCardFooter"
    }

    /** 「进入批量确认（N 项）」 entry (P7-04.D; only for a non-empty selection). */
    data class BatchConfirmButton(
        val selectionCount: Int,
    ) : ImportReviewRenderItem {
        override val stableKey: String = "batch-confirm-button"
        override val contentType: String = "batchConfirmButton"
    }

    /** 「批量结果」 header of the retained per-item summary (批量结果不设独立顶层态). */
    data object BatchResultHeader : ImportReviewRenderItem {
        override val stableKey: String = "batch-result-header"
        override val contentType: String = "sectionHeader"
    }

    /** One per-item copy line of the retained batch summary ([importBatchResultLines], verbatim). */
    data class BatchResultLine(
        val ordinal: Int,
        val line: String,
    ) : ImportReviewRenderItem {
        override val stableKey: String = "batch-line:$ordinal"
        override val contentType: String = "batchResultLine"
    }

    /** The 核对 entry of one Unknown batch item (table 6.2a: 核对入口在 IMPORT 结果摘要内). */
    data class UnknownCheckItem(
        val candidateId: ImportCandidateId,
    ) : ImportReviewRenderItem {
        override val stableKey: String = "batch-unknown:${candidateId.value}"
        override val contentType: String = "unknownCheckButton"
    }
}

/** The placement slot of one [ImportReviewRenderItem.SectionDivider]. */
internal enum class ImportReviewDividerSlot {
    /** Between the title/notice block and the format matrix (divider then trailing spacing). */
    FORMATS,

    /** Between the format/session block and the 待确认草稿 section (spacing around the divider). */
    PENDING,

    /** Inside the batch result block, before the 批量结果 header (spacing around the divider). */
    BATCH_RESULT,
}

/**
 * Projects the IMPORT overview projection into the flat render model, preserving the frozen
 * presentation semantics exactly (C1–C6): the banner iff a notice is active and immediately after
 * the title bar; the 待确认 shell with the unloaded vs 暂无导入候选 empty states (an active notice
 * suppresses the no-candidates state); the six-class frozen group order with empty groups omitted
 * and counting headers; the candidate rows with their selection state; the current-session
 * batch-disposition entry (P704SPEC-12), the open disposition card, the non-empty-selection batch
 * confirmation entry (P7-04.D) and the retained batch result as one divider band + header, every
 * summary line as its own item and one check entry per Unknown item (table 6.2a).
 */
internal fun importReviewRenderItems(
    view: ImportReviewView?,
    platform: ImportPlatformKind?,
): List<ImportReviewRenderItem> {
    val items = mutableListOf<ImportReviewRenderItem>()
    items += ImportReviewRenderItem.TitleBar
    view?.notice?.let { items += ImportReviewRenderItem.NoticeBanner(it) }
    items += ImportReviewRenderItem.SectionDivider(ImportReviewDividerSlot.FORMATS)
    items += ImportReviewRenderItem.FormatSectionHeader
    (platform?.let { importFormatEntries(it) } ?: emptyList()).forEach { entry ->
        items += ImportReviewRenderItem.FormatEntry(entry)
    }
    view?.lastIntakeSession?.let { session ->
        items += ImportReviewRenderItem.IntakeSessionHeader
        importIntakeSessionLines(session).forEachIndexed { ordinal, line ->
            items += ImportReviewRenderItem.IntakeSessionLine(ordinal, line)
        }
        val intakeRecords =
            (session.outcome as? ImportIntakePipelineOutcome.Intaken)
                ?.let { outcome ->
                    when (val intaken = outcome.outcome) {
                        is ImportFileIntakeOutcome.Accepted -> intaken.records
                        is ImportFileIntakeOutcome.NoChangeAll -> intaken.records
                        is ImportFileIntakeOutcome.Rejected -> null
                    }
                }
        intakeRecords?.let { records ->
            importIntakeRecordLines(records).forEachIndexed { ordinal, line ->
                items += ImportReviewRenderItem.IntakeRecordLine(ordinal, line)
            }
        }
    }
    items += ImportReviewRenderItem.SectionDivider(ImportReviewDividerSlot.PENDING)
    items += ImportReviewRenderItem.PendingSectionHeader
    if (view == null) {
        items += ImportReviewRenderItem.UnloadedEmptyState
    } else if (view.notice == null && view.rows.isEmpty()) {
        items += ImportReviewRenderItem.NoCandidatesEmptyState
    }
    if (view != null) {
        importCandidateClassGroups(view.rows).forEach { group ->
            items += ImportReviewRenderItem.GroupHeader(group.classToken, group.rows.size)
            group.rows.forEach { row ->
                items += ImportReviewRenderItem.CandidateItem(row, selected = row.candidateId in view.selectedCandidateIds)
            }
        }
        // P704SPEC-12: the affordance covers only the current pick session's suspected-duplicate
        // group (同次选择句柄 + EXACT_BUSINESS_TUPLE + DEFERRED via the folded row projection).
        val sessionInputRef = view.lastIntakeSession?.inputRef
        if (sessionInputRef != null && importDuplicateGroupRows(view.rows, sessionInputRef).isNotEmpty()) {
            items += ImportReviewRenderItem.GroupDispositionButton
        }
        // Option A windowing: the open 整组确认页 card expands flat as one header + one item per
        // group member + one footer, so the LazyColumn windows the card's per-item rows exactly
        // like the candidate list (no eager per-item composition inside a single card item).
        view.groupDisposition?.let { page ->
            items += ImportReviewRenderItem.GroupDispositionCardHeader(page.inputRef, page.items.size)
            page.items.forEach { items += ImportReviewRenderItem.GroupDispositionItem(it) }
            items += ImportReviewRenderItem.GroupDispositionCardFooter(page.items.size)
        }
        // P7-04.D: the batch confirmation entry is reached only with a non-empty selection.
        if (view.selectedCandidateIds.isNotEmpty()) {
            items += ImportReviewRenderItem.BatchConfirmButton(view.selectedCandidateIds.size)
        }
        // P7-04.D: the retained per-item summary expands line by line (批量结果不设独立顶层态; the
        // Unknown items keep their 核对入口 after the lines, table 6.2a).
        view.batchResult?.let { summary ->
            items += ImportReviewRenderItem.SectionDivider(ImportReviewDividerSlot.BATCH_RESULT)
            items += ImportReviewRenderItem.BatchResultHeader
            importBatchResultLines(summary).forEachIndexed { ordinal, line ->
                items += ImportReviewRenderItem.BatchResultLine(ordinal, line)
            }
            summary.items
                .filter { it.outcome is ImportBatchItemOutcome.Unknown }
                .forEach { entry ->
                    items += ImportReviewRenderItem.UnknownCheckItem(entry.item.candidateId)
                }
        }
    }
    return items
}
