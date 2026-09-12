package com.unifiedledger.ui

import com.unifiedledger.application.ExpenseDraft
import com.unifiedledger.application.IncomeDraft
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.ManualExpenseAmountFormatError
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.ParseManualExpenseOccurredAt
import com.unifiedledger.application.TransferDraft
import com.unifiedledger.application.TypedEntryDraft
import com.unifiedledger.domain.CurrencyUnit
import kotlin.time.Instant

/**
 * Pure field-completeness and amount-format validation shared by the reducer's `Continue` branch
 * and the edit screen's error display (finding P503Q-015). No IO, no randomness, no facade calls.
 * [ledgerClock] is a constructor-injected parameterized input read only inside
 * [occurredAtTextReconciles] (D-138 spec 2.3). The parse currency is resolved per draft from the
 * selected primary account; the caller supplies it.
 *
 * P7-02: the per-type fields are validated type-aware. The expense and income drafts require the
 * primary account, a secondary category and an amount. The transfer draft requires both drawers,
 * a destination credit, a fee (default `0.00`) and, only when the fee is positive, a fee category.
 */
data class P503DraftErrors(
    val missingAmount: Boolean,
    val missingPaymentAccount: Boolean,
    val missingCategory: Boolean,
    val missingOccurredAt: Boolean,
    val amountFormatError: ManualExpenseAmountFormatError?,
    /** P7-02.B transfer: the destination drawer is missing. */
    val missingDestinationAccount: Boolean = false,
    /** P7-02.B transfer: the fee text is present but not a valid amount. */
    val feeFormatError: ManualExpenseAmountFormatError? = null,
    /** P7-02.B transfer: the fee is positive, so a fee category is required/selectable. */
    val transferFeeCategoryRequired: Boolean = false,
) {
    val hasErrors: Boolean
        get() =
            missingAmount ||
                missingPaymentAccount ||
                missingCategory ||
                missingOccurredAt ||
                amountFormatError != null ||
                missingDestinationAccount ||
                feeFormatError != null
}

class P503DraftValidation(
    private val parseAmount: ParseManualExpenseAmount,
    private val parseOccurredAt: ParseManualExpenseOccurredAt = ParseManualExpenseOccurredAt(),
    private val ledgerClock: LedgerClock = LedgerClock { error("D-138: occurred-at reconciliation needs the injected ledger clock") },
) {
    fun errors(
        draft: TypedEntryDraft,
        currency: CurrencyUnit,
    ): P503DraftErrors =
        when (draft) {
            is TransferDraft -> transferErrors(draft, currency)
            is ExpenseDraft, is IncomeDraft -> standardErrors(draft, currency)
        }

    fun isValid(
        draft: TypedEntryDraft,
        currency: CurrencyUnit,
    ): Boolean = !errors(draft, currency).hasErrors

    private fun standardErrors(
        draft: TypedEntryDraft,
        currency: CurrencyUnit,
    ): P503DraftErrors {
        val amountText = draft.amountText
        val amountError =
            if (amountText.isBlank()) {
                null
            } else {
                when (val parsed = parseAmount.parse(amountText, currency)) {
                    is ParseManualExpenseAmount.Result.Valid -> null
                    is ParseManualExpenseAmount.Result.Invalid -> parsed.error
                }
            }
        return P503DraftErrors(
            missingAmount = amountText.isBlank(),
            missingPaymentAccount = draft.primaryAccountId == null,
            missingCategory = draft.categoryId == null,
            missingOccurredAt = draft.occurredAt == null,
            amountFormatError = amountError,
        )
    }

    private fun transferErrors(
        draft: TransferDraft,
        currency: CurrencyUnit,
    ): P503DraftErrors {
        val creditText = draft.destinationCredit
        val creditError =
            if (creditText.isBlank()) {
                null
            } else {
                when (val parsed = parseAmount.parse(creditText, currency)) {
                    is ParseManualExpenseAmount.Result.Valid -> null
                    is ParseManualExpenseAmount.Result.Invalid -> parsed.error
                }
            }
        val feeText = draft.fee
        val feeMinor =
            if (feeText.isBlank()) {
                null
            } else {
                (parseAmount.parse(feeText, currency) as? ParseManualExpenseAmount.Result.Valid)?.minorUnits
            }
        val feeError =
            if (feeText.isBlank()) {
                null
            } else {
                when (val parsed = parseAmount.parse(feeText, currency)) {
                    is ParseManualExpenseAmount.Result.Valid -> null
                    is ParseManualExpenseAmount.Result.Invalid -> parsed.error
                }
            }
        // T-4: a positive fee requires a fee category; a zero fee must not carry one.
        val feeRequiresCategory = (feeMinor ?: 0L) > 0L
        return P503DraftErrors(
            missingAmount = creditText.isBlank(),
            missingPaymentAccount = draft.sourceAccountId == null,
            missingDestinationAccount = draft.destinationAccountId == null,
            missingCategory = feeRequiresCategory && draft.feeCategoryId == null,
            missingOccurredAt = draft.occurredAt == null,
            amountFormatError = creditError,
            feeFormatError = feeError,
            transferFeeCategoryRequired = feeRequiresCategory,
        )
    }

    /**
     * Continue gate for the occurred-at field (finding P503IMPL-Q-001): the edit screen keeps its
     * own occurred-at text; Continue may only proceed when that text re-parses to exactly the
     * draft's instant, so the displayed value can never differ from what the reducer validates.
     * D-138: the text re-parses with the lenient [ParseManualExpenseOccurredAt] and the same
     * [ledgerClock] source the screen uses (spec 2.3).
     */
    fun occurredAtTextReconciles(
        text: String,
        draftOccurredAt: Instant?,
    ): Boolean {
        if (text.isBlank()) {
            return draftOccurredAt == null
        }
        val parsed = parseOccurredAt.parse(text, ledgerClock)
        return parsed is ParseManualExpenseOccurredAt.Result.Valid && parsed.instant == draftOccurredAt
    }
}
