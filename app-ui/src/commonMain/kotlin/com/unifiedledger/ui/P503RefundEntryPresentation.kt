package com.unifiedledger.ui

import com.unifiedledger.application.EntryType
import com.unifiedledger.application.ManualRefundCandidate
import com.unifiedledger.application.ManualRefundCandidatesResult
import com.unifiedledger.application.ManualRefundRejectionCode

/*
 * P8-03 i2b (D-312; spec sections 2.2/2.3/2.6) — the refund-receipt entry's pure presentation
 * layer: the type-list projection, the candidate list's typed states and copy, and the frozen
 * rejection/unknown copy. No Compose API here, so commonTest pins every string and every state
 * transition without a Compose harness.
 */

/**
 * The edit screen's type radio list (spec section 2.2): the delivered types in their frozen enum
 * order, with `REFUND_RECEIPT` omitted while the composition root has not wired the refund surface
 * ([refundEntryAvailable] false returns exactly the pre-P8-03 five, so an unwired root renders no
 * dead refund choice). Deriving from the enum keeps a future `EntryType` member from silently
 * disappearing out of the list: it can only be added together with its exhaustive label below.
 */
internal fun entryTypeChoices(refundEntryAvailable: Boolean): List<EntryType> = EntryType.entries.filter { refundEntryAvailable || it != EntryType.REFUND_RECEIPT }

/** The type radio's label. Exhaustive with no `else`: a new type must be given a label. */
internal fun entryTypeLabel(type: EntryType): String =
    when (type) {
        EntryType.EXPENSE -> "支出"
        EntryType.INCOME -> "收入"
        EntryType.TRANSFER -> "转账"
        EntryType.LEND -> "借出"
        EntryType.COLLECT -> "收回"
        EntryType.REFUND_RECEIPT -> "退款到账"
    }

/**
 * The candidate list's four renderable states (spec sections 2.3/5, V-13). `null` (never read)
 * is [Loading]; a typed read failure is [Failed]; only a genuinely empty page is [Empty]. The two
 * "nothing to pick" states are therefore never interchangeable, so a failed read can never be
 * presented as an authoritative empty list.
 */
internal sealed interface RefundCandidatesView {
    data object Loading : RefundCandidatesView

    data object Empty : RefundCandidatesView

    data object Failed : RefundCandidatesView

    data class Loaded(
        val rows: List<ManualRefundCandidate>,
    ) : RefundCandidatesView
}

internal fun refundCandidatesView(result: ManualRefundCandidatesResult?): RefundCandidatesView =
    when (result) {
        null -> RefundCandidatesView.Loading
        ManualRefundCandidatesResult.Unavailable -> RefundCandidatesView.Failed
        is ManualRefundCandidatesResult.Loaded ->
            if (result.candidates.isEmpty()) {
                RefundCandidatesView.Empty
            } else {
                RefundCandidatesView.Loaded(result.candidates)
            }
    }

internal const val REFUND_CANDIDATES_LOADING_TEXT: String = "正在读取可退交易…"

internal const val REFUND_CANDIDATES_EMPTY_TEXT: String = "暂无可退交易"

internal const val REFUND_CANDIDATES_FAILED_TEXT: String = "可退交易读取失败"

internal const val REFUND_CANDIDATES_RETRY_TEXT: String = "重试"

/** The frozen notice of a non-row state; `null` when the view renders rows instead. */
internal fun refundCandidatesNotice(view: RefundCandidatesView): String? =
    when (view) {
        RefundCandidatesView.Loading -> REFUND_CANDIDATES_LOADING_TEXT
        RefundCandidatesView.Empty -> REFUND_CANDIDATES_EMPTY_TEXT
        RefundCandidatesView.Failed -> REFUND_CANDIDATES_FAILED_TEXT
        is RefundCandidatesView.Loaded -> null
    }

/** Only a typed read failure offers the retry affordance (spec section 2.3 / V-13). */
internal fun refundCandidatesRetryable(view: RefundCandidatesView): Boolean = view is RefundCandidatesView.Failed

/**
 * One candidate row: `date · category · expense-leg amount · refunded · remaining` (spec section
 * 2.3). The expense-leg amount is the CAP BASE, never the original transaction's total; the
 * refunded part is `cap − remaining`, derived in exact minor units (no binary floating point).
 * The category name is the catalog's current display name of the inherited secondary category,
 * shown read-only because the user never picks it (spec section 2.4).
 */
internal fun refundCandidateRowText(candidate: ManualRefundCandidate): String {
    val precision = candidate.structuralCap.currency.precision
    val refundedMinor = candidate.structuralCap.minorUnits - candidate.remaining.minorUnits
    return listOf(
        occurredAtLocalDateText(candidate.occurredAt),
        candidate.categoryName,
        "支出腿金额 ${formatMinorUnits(candidate.structuralCap.minorUnits, precision)}",
        "已退 ${formatMinorUnits(refundedMinor, precision)}",
        "剩余 ${formatMinorUnits(candidate.remaining.minorUnits, precision)}",
    ).joinToString(" · ")
}

/**
 * The frozen per-code rejection copy (D9; the eight-code closed set of spec section 2.6). Every
 * code has exactly one sentence; no code falls back to a shared message, so the user always reads
 * why the refund was refused and whether the fix is the amount or the original selection.
 */
internal fun refundRejectionText(code: ManualRefundRejectionCode): String =
    when (code) {
        ManualRefundRejectionCode.REFUND_CATEGORY_INELIGIBLE -> "退款分类不可用"
        ManualRefundRejectionCode.REFUND_ACCOUNT_INELIGIBLE -> "到账账户不可用"
        ManualRefundRejectionCode.ORIGINAL_NOT_REFUNDABLE -> "原交易当前不可退"
        ManualRefundRejectionCode.ORIGINAL_NO_LONGER_REFUNDABLE -> "原交易已被作废或修改，请重新选择"
        ManualRefundRejectionCode.REFUND_EXCEEDS_STRUCTURAL_CAP -> "退款金额超过原交易的支出金额"
        ManualRefundRejectionCode.REFUND_EXCEEDS_REMAINING -> "退款金额超过剩余可退金额"
        ManualRefundRejectionCode.REFUND_REQUEST_CONFLICT -> "该请求与已有记录不一致，请重新进入"
        ManualRefundRejectionCode.REFUND_STORE_UNAVAILABLE -> "退款保存失败，请稍后重试"
    }

/** Spec section 2.6's verbatim unknown-outcome sentence (D9): never retried, re-entered instead. */
internal const val REFUND_UNKNOWN_RESULT_TEXT: String = "结果未知，请重开确认"
