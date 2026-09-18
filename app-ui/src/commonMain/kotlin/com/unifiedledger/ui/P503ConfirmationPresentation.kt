package com.unifiedledger.ui

import com.unifiedledger.application.CollectDraft
import com.unifiedledger.application.EntryType
import com.unifiedledger.application.ExpenseDraft
import com.unifiedledger.application.IncomeDraft
import com.unifiedledger.application.LendDraft
import com.unifiedledger.application.TransferDraft
import com.unifiedledger.application.TypedEntryDraft
import com.unifiedledger.domain.parseExactDecimalLenient

/**
 * A-02 FIX-CONFIRM-1: the confirmation page's type-aware presentation (spec section 4). Pure copy
 * and exact-decimal derivation only — no Compose API, so commonTest pins every type's title and
 * row set directly.
 */
internal data class ConfirmationRow(
    val label: String,
    val value: String,
)

/**
 * The host-resolved display labels of one confirmation page. [paymentAccount] and [category] keep
 * their per-type meanings (the draft's primary account and the type's category slot);
 * [destinationAccount] and [counterparty] are populated only by the types that own those rows, so
 * a page never borrows another type's object for its own label.
 */
internal data class ConfirmationLabels(
    val paymentAccount: String,
    val category: String,
    val destinationAccount: String? = null,
    val counterparty: String? = null,
)

/** The absent-value placeholder shared with the note and occurred-at lines. */
private const val ABSENT_VALUE = "—"

/** The type-aware confirmation title (spec section 4.1 item 1). */
internal fun confirmationTitle(draft: TypedEntryDraft): String =
    when (draft.entryType) {
        EntryType.EXPENSE -> "确认支出"
        EntryType.INCOME -> "确认收入"
        EntryType.TRANSFER -> "确认转账"
        EntryType.LEND -> "确认借出"
        EntryType.COLLECT -> "确认收回"
    }

/**
 * The ordered label/value rows of the confirmation page (spec section 4.1 item 2).
 *
 * Transfers show the destination credit and the fee next to the derived total debit, and collects
 * show the principal/interest composition next to the carried total: a single amount would be read
 * as the debit on a transfer and would hide the composition on a collect, so both values stay
 * visible before confirmation (ACCOUNTING_RULES.md:76 requires the exact composition amounts to be
 * explicitly presented; PRODUCT_REQUIREMENTS.md:11 keeps internal transfers distinct from income
 * and expense).
 *
 * Non-blank component amounts render the draft's already-validated text verbatim; a blank
 * component is absent, because the gate accepts a blank component and the submission path stores
 * it as zero. Only the transfer total debit is derived — the transfer draft stores no debit field
 * (P1-2) — and it is derived exactly, at [currencyPrecision], so the total always renders at the
 * currency's money scale.
 */
internal fun confirmationRows(
    draft: TypedEntryDraft,
    labels: ConfirmationLabels,
    currencyCode: String,
    currencyPrecision: Int,
): List<ConfirmationRow> =
    when (draft) {
        is ExpenseDraft ->
            listOf(
                ConfirmationRow("支付账户", labels.paymentAccount),
                ConfirmationRow("费用分类", labels.category),
                ConfirmationRow("金额", amountValue(draft.amountText, currencyCode)),
            )
        is IncomeDraft ->
            listOf(
                ConfirmationRow("收款账户", labels.paymentAccount),
                ConfirmationRow("收入分类", labels.category),
                ConfirmationRow("金额", amountValue(draft.amountText, currencyCode)),
            )
        is TransferDraft ->
            buildList {
                add(ConfirmationRow("转出账户", labels.paymentAccount))
                add(ConfirmationRow("转入账户", labels.destinationAccount ?: ABSENT_VALUE))
                add(ConfirmationRow("到账本金", amountValue(draft.destinationCredit, currencyCode)))
                add(ConfirmationRow("手续费", amountValue(draft.fee, currencyCode)))
                // T-4: a zero or blank fee never carries a fee category, so the row is absent.
                if (isPositiveAmount(draft.fee, currencyPrecision)) add(ConfirmationRow("手续费分类", labels.category))
                add(ConfirmationRow("转出总额", amountValue(exactSumText(draft.destinationCredit, draft.fee, currencyPrecision), currencyCode)))
            }
        is LendDraft ->
            listOf(
                ConfirmationRow("往来对象", labels.counterparty ?: ABSENT_VALUE),
                ConfirmationRow("出资账户", labels.paymentAccount),
                ConfirmationRow("借出金额", amountValue(draft.amount, currencyCode)),
            )
        is CollectDraft ->
            buildList {
                add(ConfirmationRow("往来对象", labels.counterparty ?: ABSENT_VALUE))
                add(ConfirmationRow("到账账户", labels.paymentAccount))
                add(ConfirmationRow("本金", amountValue(draft.principal, currencyCode)))
                add(ConfirmationRow("利息", amountValue(draft.interest, currencyCode)))
                // The interest category is only meaningful when interest is positive (T-4 analogue).
                if (isPositiveAmount(draft.interest, currencyPrecision)) add(ConfirmationRow("利息分类", labels.category))
                // The collect draft already carries the total received; it is rendered as-is
                // (spec 4.2), never recomputed from the components.
                add(ConfirmationRow("实收总额", amountValue(draft.totalReceived, currencyCode)))
            }
    }

/**
 * A blank or absent component renders as the absent placeholder, never as a bare currency code.
 * A non-blank component renders the draft's text verbatim: the page shows what the gate accepted
 * and never normalizes the user's input.
 */
private fun amountValue(
    text: String,
    currencyCode: String,
): String = if (text.isBlank() || text == ABSENT_VALUE) ABSENT_VALUE else "$text $currencyCode"

/**
 * Exact decimal sum of two amount texts, parsed and rendered at [precision]. A blank operand is
 * zero (the submission path stores a blank transfer fee as zero), so the derived total stays
 * visible and equal to the saved debit. A non-blank operand that does not parse at [precision]
 * keeps the total absent instead of evaluated (spec 4.3 item 7). The addition is integer
 * minor-unit arithmetic (no binary floating point); two operands can each parse within their own
 * limits and still sum past `Long.MAX_VALUE`, so both overflow directions are rejected.
 */
private fun exactSumText(
    left: String,
    right: String,
    precision: Int,
): String {
    val leftMinor = if (left.isBlank()) 0L else parseExactDecimalLenient(left, precision) ?: return ABSENT_VALUE
    val rightMinor = if (right.isBlank()) 0L else parseExactDecimalLenient(right, precision) ?: return ABSENT_VALUE
    if (rightMinor > 0L && leftMinor > Long.MAX_VALUE - rightMinor) return ABSENT_VALUE
    if (rightMinor < 0L && leftMinor < Long.MIN_VALUE - rightMinor) return ABSENT_VALUE
    return formatMinorUnits(leftMinor + rightMinor, precision)
}

private fun isPositiveAmount(
    text: String,
    precision: Int,
): Boolean = (parseExactDecimalLenient(text, precision) ?: 0L) > 0L
