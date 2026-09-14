package com.unifiedledger.ui

import com.unifiedledger.application.ImportDuplicateCandidateKind
import com.unifiedledger.application.ImportDuplicateReviewRow
import com.unifiedledger.application.ImportDuplicateReviewsResult
import com.unifiedledger.application.ImportDuplicateStatus
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
