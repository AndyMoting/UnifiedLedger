package com.unifiedledger.ui

import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.ManualExpenseAmountFormatError
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.ParseManualExpenseOccurredAt
import com.unifiedledger.domain.CurrencyUnit
import kotlin.time.Instant

/**
 * Pure field-completeness and amount-format validation shared by the reducer's
 * `Continue` branch and the edit screen's error display (finding P503Q-015). No IO, no
 * randomness, no facade calls. [ledgerClock] is a constructor-injected parameterized input
 * read only inside [occurredAtTextReconciles] (D-138 spec 2.3), so the predicates depend on
 * no global or mutable state; the fail-fast default exists because the reducer's internal
 * instance never reconciles occurred-at text. The parse currency is resolved per draft from
 * the selected payment account (spec section 4.1); the caller supplies it.
 */
data class P503DraftErrors(
    val missingAmount: Boolean,
    val missingPaymentAccount: Boolean,
    val missingCategory: Boolean,
    val missingOccurredAt: Boolean,
    val amountFormatError: ManualExpenseAmountFormatError?,
) {
    val hasErrors: Boolean
        get() = missingAmount || missingPaymentAccount || missingCategory || missingOccurredAt || amountFormatError != null
}

class P503DraftValidation(
    private val parseAmount: ParseManualExpenseAmount,
    private val parseOccurredAt: ParseManualExpenseOccurredAt = ParseManualExpenseOccurredAt(),
    private val ledgerClock: LedgerClock = LedgerClock { error("D-138: occurred-at reconciliation needs the injected ledger clock") },
) {
    fun errors(
        draft: ManualExpenseDraft,
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
            missingPaymentAccount = draft.paymentAccountId == null,
            missingCategory = draft.categoryId == null,
            missingOccurredAt = draft.occurredAt == null,
            amountFormatError = amountError,
        )
    }

    fun isValid(
        draft: ManualExpenseDraft,
        currency: CurrencyUnit,
    ): Boolean = !errors(draft, currency).hasErrors

    /**
     * Continue gate for the occurred-at field (finding P503IMPL-Q-001): the edit screen
     * keeps its own occurred-at text; Continue may only proceed when that text re-parses to
     * exactly the draft's instant, so the displayed value can never differ from what the
     * reducer validates. D-138: the text re-parses with the lenient
     * [ParseManualExpenseOccurredAt] and the same [ledgerClock] source the screen uses
     * (spec 2.3), so the gate and the screen share one parser and one clock instance.
     * Blank text reconciles to a null draft (the reducer then reports the missing-field
     * error); any other text that does not parse, or parses to a different instant, blocks
     * Continue.
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
