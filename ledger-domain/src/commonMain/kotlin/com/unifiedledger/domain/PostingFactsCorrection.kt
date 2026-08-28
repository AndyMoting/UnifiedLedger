package com.unifiedledger.domain

/**
 * D-085 RG-12 neutral facts of one posting as compared by the frozen validator: `account_id`,
 * `amount` (exact decimal text), `currency`, `role` and optional `category_id`. The shared
 * domain [Posting] core type carries only account and amount, so posting-facts corrections and
 * replacement links compare facts through this type; the application layer supplies the facts
 * of the old current posting set from its persistence view.
 */
data class PostingFacts(
    val accountId: AccountId,
    val amountText: String,
    val currency: CurrencyUnit,
    val role: String,
    val categoryId: CategoryId?,
) {
    /**
     * Golden facts equality: account id, amount text, currency code, role and category id.
     * Currency compares by code like the validator's string comparison, independent of the
     * precision the caller parsed the input currency with.
     */
    fun sameAs(other: PostingFacts): Boolean =
        accountId == other.accountId &&
            amountText == other.amountText &&
            currency.code == other.currency.code &&
            role == other.role &&
            categoryId == other.categoryId
}

/** One `replacement_postings` item of the frozen posting_facts input (design doc, line 13). */
data class ReplacementPostingInput(
    val sourcePostingId: PostingId,
    val facts: PostingFacts,
)

/**
 * The `history_mutation` input shape of the frozen contract; any presence is rejected with
 * `historical_facts_immutable` (reject-10).
 */
data class HistoryMutationInput(
    val matchId: String,
    val statusHistory: List<ReconciliationMatchStatusEntry>,
)

/**
 * The posting_facts correction attempt as seen by the domain. [transaction] is the resolved
 * current transaction of the input `transaction_id` (`null` when the id is unknown; the
 * golden validator reports that as `complete_replacement_postings_required`).
 */
data class PostingFactsCorrectionAttempt(
    val transaction: FormalTransaction?,
    val replacementPostings: List<ReplacementPostingInput>,
    val explicitConfirmation: Boolean,
    val historyMutation: HistoryMutationInput? = null,
)

/**
 * Validates a `posting_facts` correction attempt against the current transaction, catalog and
 * reconciliation facts, mirroring the frozen first-failure order of the golden validator
 * (`_posting_facts_correction_failure`, tools/python/golden_cases/v2.py, lines 427-520):
 *
 * 1. unknown transaction, wrong replacement count, or sources not covering the old current
 *    posting set -> `complete_replacement_postings_required`;
 * 2. per-currency imbalance or non-numeric amount -> `replacement_postings_must_balance`;
 * 3. first duplicate `source_posting_id` -> `duplicate_source_posting_id`;
 * 4. per item: unknown account -> `known_account_required`, non-owned non-expense account ->
 *    `owned_account_required`, currency mismatch -> `account_currency_mismatch`;
 * 5. per item, when [rejectChangedMatchedAsset] (the frozen rejection path): a `matched`
 *    asset posting whose facts changed -> `matched_unaffected_posting_must_be_preserved`;
 *    the accepted path passes `false` and expresses the change through symmetric
 *    preserved/invalidated lineage instead (test changed_asset_case);
 * 6. missing user confirmation -> `explicit_confirmation_required`;
 * 7. first amount that is not an exact decimal string of the currency precision ->
 *    `exact_decimal_string_required`;
 * 8. any `history_mutation` -> `historical_facts_immutable`.
 *
 * [accounts] is the ledger catalog by account id, [oldFactsByPosting] the facts of the old
 * current posting set by posting id, and [reconciliationsByPosting] the current
 * `posting_reconciliations` status by posting id.
 */
fun validatePostingFactsCorrection(
    attempt: PostingFactsCorrectionAttempt,
    accounts: Map<AccountId, Account>,
    oldFactsByPosting: Map<PostingId, PostingFacts>,
    reconciliationsByPosting: Map<PostingId, PostingReconciliationStatus>,
    rejectChangedMatchedAsset: Boolean = true,
): DomainResult<Unit> {
    // 1. unknown transaction (validator: transaction lookup failure -> complete_replacement_postings_required).
    val transaction =
        attempt.transaction
            ?: return DomainResult.Failure(CorrectTransactionVersionViolation.CompleteReplacementPostingsRequired())
    val oldPostingIds = transaction.currentPostings().map { it.id }

    // 1. replacement count must equal the old current posting count.
    if (attempt.replacementPostings.size != oldPostingIds.size) {
        return DomainResult.Failure(CorrectTransactionVersionViolation.CompleteReplacementPostingsRequired())
    }

    // 2. exact per-currency balance over the raw amount texts.
    if (!replacementSetBalances(attempt.replacementPostings)) {
        return DomainResult.Failure(CorrectTransactionVersionViolation.ReplacementPostingsMustBalance())
    }

    // 3. source posting ids must be unique; the first duplicate is reported at its index.
    val seenSources = mutableSetOf<PostingId>()
    attempt.replacementPostings.forEachIndexed { index, item ->
        if (!seenSources.add(item.sourcePostingId)) {
            return DomainResult.Failure(CorrectTransactionVersionViolation.DuplicateSourcePostingId(index))
        }
    }

    // 1. the source set must cover the old current posting set exactly once.
    if (seenSources != oldPostingIds.toSet()) {
        return DomainResult.Failure(CorrectTransactionVersionViolation.CompleteReplacementPostingsRequired())
    }

    // 4. per item account checks: known, owned (or expense pseudo-account), currency match.
    attempt.replacementPostings.forEachIndexed { index, item ->
        val account =
            accounts[item.facts.accountId]
                ?: return DomainResult.Failure(CorrectTransactionVersionViolation.KnownAccountRequired(index))
        val ownedOrExpensePseudoAccount =
            account.ownedByUser || (account.kind == AccountKind.EXPENSE && !account.realAccount)
        if (!ownedOrExpensePseudoAccount) {
            return DomainResult.Failure(CorrectTransactionVersionViolation.OwnedAccountRequired(index))
        }
        if (account.currency != item.facts.currency) {
            return DomainResult.Failure(CorrectTransactionVersionViolation.AccountCurrencyMismatch(index))
        }
    }

    // 5. matched asset legs must be preserved unless the accepted path opts into symmetric lineage.
    if (rejectChangedMatchedAsset) {
        attempt.replacementPostings.forEachIndexed { index, item ->
            val oldFacts =
                oldFactsByPosting[item.sourcePostingId]
                    ?: return DomainResult.Failure(CorrectTransactionVersionViolation.IncompleteOldPostingFacts)
            val oldAccount =
                accounts[oldFacts.accountId]
                    ?: return DomainResult.Failure(CorrectTransactionVersionViolation.IncompleteOldPostingFacts)
            val oldMatched =
                reconciliationsByPosting[item.sourcePostingId] == PostingReconciliationStatus.MATCHED
            if (oldMatched && oldAccount.kind == AccountKind.ASSET && !item.facts.sameAs(oldFacts)) {
                return DomainResult.Failure(
                    CorrectTransactionVersionViolation.MatchedUnaffectedPostingMustBePreserved(index),
                )
            }
        }
    }

    // 6. the user must explicitly confirm the correction.
    if (!attempt.explicitConfirmation) {
        return DomainResult.Failure(CorrectTransactionVersionViolation.ExplicitConfirmationRequired)
    }

    // 7. every amount must be an exact decimal string of its currency precision.
    attempt.replacementPostings.forEachIndexed { index, item ->
        if (parseExactDecimal(item.facts.amountText, item.facts.currency.precision) == null) {
            return DomainResult.Failure(CorrectTransactionVersionViolation.ExactDecimalStringRequired(index))
        }
    }

    // 8. old versions, evidence and history are immutable; any mutation input is rejected.
    if (attempt.historyMutation != null) {
        return DomainResult.Failure(CorrectTransactionVersionViolation.HistoricalFactsImmutable)
    }

    return DomainResult.Success(Unit)
}

private data class LenientDecimal(
    val negative: Boolean,
    val whole: Long,
    val fractionDigits: Int,
    val fraction: Long,
)

/**
 * Lenient decimal parse used only for the balance check, matching `Decimal(str(amount))` of
 * the golden validator: any sign, any integer digits and any fractional scale. A non-numeric
 * text (or an overflow beyond Long at the normalized scale) makes the set unbalanceable, which
 * the validator reports as `replacement_postings_must_balance`.
 */
private fun parseLenientDecimal(text: String): LenientDecimal? {
    val match = Regex("^([+-]?)([0-9]+)(?:[.]([0-9]*))?$").matchEntire(text) ?: return null
    val whole = match.groupValues[2].toLongOrNull() ?: return null
    val fractionText = match.groupValues[3]
    val fractionDigits = fractionText.length
    val fraction = if (fractionDigits == 0) 0L else fractionText.toLongOrNull() ?: return null
    return LenientDecimal(
        negative = match.groupValues[1] == "-",
        whole = whole,
        fractionDigits = fractionDigits,
        fraction = fraction,
    )
}

private fun replacementSetBalances(replacements: List<ReplacementPostingInput>): Boolean {
    val byCurrency = LinkedHashMap<String, MutableList<LenientDecimal>>()
    for (item in replacements) {
        val parsed = parseLenientDecimal(item.facts.amountText) ?: return false
        byCurrency.getOrPut(item.facts.currency.code) { mutableListOf() }.add(parsed)
    }
    for (values in byCurrency.values) {
        val maxDigits = values.maxOf { it.fractionDigits }
        val baseScale = pow10Exact(maxDigits) ?: return false
        var total = 0L
        for (value in values) {
            val shift = maxDigits - value.fractionDigits
            val shiftScale = if (shift == 0) 1L else pow10Exact(shift) ?: return false
            val wholeScaled = checkedMultiply(value.whole, baseScale) ?: return false
            val fractionScaled = checkedMultiply(value.fraction, shiftScale) ?: return false
            val magnitude = checkedAdd(wholeScaled, fractionScaled) ?: return false
            val signed = if (value.negative) checkedNegate(magnitude) ?: return false else magnitude
            total = checkedAdd(total, signed) ?: return false
        }
        if (total != 0L) return false
    }
    return true
}

private fun checkedMultiply(
    left: Long,
    right: Long,
): Long? {
    if (left != 0L && right != 0L && left > Long.MAX_VALUE / right) return null
    return left * right
}
