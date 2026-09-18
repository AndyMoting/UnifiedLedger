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
 * Component amounts render the draft's already-validated text. Only the transfer total debit is
 * derived — the transfer draft stores no debit field (P1-2) — and it is derived exactly, without
 * rounding.
 */
internal fun confirmationRows(
    draft: TypedEntryDraft,
    labels: ConfirmationLabels,
    currencyCode: String,
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
                // T-4: a zero fee never carries a fee category, so the row is absent rather than blank.
                if (isPositiveAmount(draft.fee)) add(ConfirmationRow("手续费分类", labels.category))
                add(ConfirmationRow("转出总额", amountValue(exactSumText(draft.destinationCredit, draft.fee), currencyCode)))
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
                if (isPositiveAmount(draft.interest)) add(ConfirmationRow("利息分类", labels.category))
                // The collect draft already carries the total received; it is rendered as-is
                // (spec 4.2), never recomputed from the components.
                add(ConfirmationRow("实收总额", amountValue(draft.totalReceived, currencyCode)))
            }
    }

private fun amountValue(
    text: String,
    currencyCode: String,
): String = if (text == ABSENT_VALUE) ABSENT_VALUE else "$text $currencyCode"

/**
 * Exact decimal sum of two validated amount texts, rendered at the operands' common (largest)
 * fraction scale. The Continue gate already validated both operands against the currency
 * precision, so the scale never exceeds it; the shared minor-unit parser keeps the addition in
 * integer arithmetic (no binary floating point, no rounding). The overflow guards are defensive:
 * each operand is a single parsed amount and the page renders both components regardless.
 */
private fun exactSumText(
    left: String,
    right: String,
): String {
    val scale = maxOf(fractionDigits(left), fractionDigits(right))
    val leftMinor = parseExactDecimalLenient(left, scale) ?: return ABSENT_VALUE
    val rightMinor = parseExactDecimalLenient(right, scale) ?: return ABSENT_VALUE
    if (rightMinor > 0L && leftMinor > Long.MAX_VALUE - rightMinor) return ABSENT_VALUE
    if (rightMinor < 0L && leftMinor < Long.MIN_VALUE - rightMinor) return ABSENT_VALUE
    return formatMinorUnits(leftMinor + rightMinor, scale)
}

private fun isPositiveAmount(text: String): Boolean = (exactMinorUnits(text) ?: 0L) > 0L

private fun exactMinorUnits(text: String): Long? = parseExactDecimalLenient(text, fractionDigits(text))

private fun fractionDigits(text: String): Int = text.substringAfter('.', missingDelimiterValue = "").length
