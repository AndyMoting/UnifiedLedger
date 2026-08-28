package com.unifiedledger.domain

/**
 * D-085 RG-12 violations of `correct_transaction_version` `posting_facts`, modelled after
 * [PeriodicAllocationViolation]. The ten frozen rejection reasons of `golden/rules/rg-12.json`
 * (operation-reject-1..10) map one-to-one through [reasonCode] and [fieldPath] to the closed
 * `outcome.field_path` recomputed by the validator
 * (`_posting_facts_correction_failure`, tools/python/golden_cases/v2.py):
 *
 * - `complete_replacement_postings_required` -> `$.attempted_input.replacement_postings`
 * - `replacement_postings_must_balance` -> `$.attempted_input.replacement_postings`
 * - `duplicate_source_posting_id` -> `$.attempted_input.replacement_postings[i].source_posting_id`
 * - `known_account_required` -> `$.attempted_input.replacement_postings[i].account_id`
 * - `owned_account_required` -> `$.attempted_input.replacement_postings[i].account_id`
 * - `account_currency_mismatch` -> `$.attempted_input.replacement_postings[i].account_id`
 * - `matched_unaffected_posting_must_be_preserved` -> `$.attempted_input.replacement_postings[i]`
 * - `explicit_confirmation_required` -> `$.attempted_input.explicit_confirmation`
 * - `exact_decimal_string_required` -> `$.attempted_input.replacement_postings[i].amount`
 * - `historical_facts_immutable` -> `$.attempted_input.history_mutation`
 *
 * The remaining members guard entity-internal integrity only and carry `reasonCode == null`;
 * they never produce a frozen rejection reason by themselves.
 */
sealed interface CorrectTransactionVersionViolation : DomainViolation {
    val reasonCode: String?
    val fieldPath: String

    /** reason `complete_replacement_postings_required` (reject-1: unknown transaction, wrong count, uncovered sources). */
    data class CompleteReplacementPostingsRequired(
        override val fieldPath: String = "$.attempted_input.replacement_postings",
    ) : CorrectTransactionVersionViolation {
        override val reasonCode: String? = "complete_replacement_postings_required"
    }

    /** reason `replacement_postings_must_balance` (reject-2: per-currency imbalance or non-numeric amount). */
    data class ReplacementPostingsMustBalance(
        override val fieldPath: String = "$.attempted_input.replacement_postings",
    ) : CorrectTransactionVersionViolation {
        override val reasonCode: String? = "replacement_postings_must_balance"
    }

    /** reason `duplicate_source_posting_id` (reject-3, reported at the second occurrence index). */
    data class DuplicateSourcePostingId(
        val index: Int,
    ) : CorrectTransactionVersionViolation {
        override val reasonCode: String? = "duplicate_source_posting_id"
        override val fieldPath: String = "$.attempted_input.replacement_postings[$index].source_posting_id"
    }

    /** reason `known_account_required` (reject-4: account not in the catalog). */
    data class KnownAccountRequired(
        val index: Int,
    ) : CorrectTransactionVersionViolation {
        override val reasonCode: String? = "known_account_required"
        override val fieldPath: String = "$.attempted_input.replacement_postings[$index].account_id"
    }

    /** reason `owned_account_required` (reject-5: not owned and not an expense pseudo-account). */
    data class OwnedAccountRequired(
        val index: Int,
    ) : CorrectTransactionVersionViolation {
        override val reasonCode: String? = "owned_account_required"
        override val fieldPath: String = "$.attempted_input.replacement_postings[$index].account_id"
    }

    /** reason `account_currency_mismatch` (reject-6: account currency differs from the input currency). */
    data class AccountCurrencyMismatch(
        val index: Int,
    ) : CorrectTransactionVersionViolation {
        override val reasonCode: String? = "account_currency_mismatch"
        override val fieldPath: String = "$.attempted_input.replacement_postings[$index].account_id"
    }

    /** reason `matched_unaffected_posting_must_be_preserved` (reject-7, whole item of the changed matched asset leg). */
    data class MatchedUnaffectedPostingMustBePreserved(
        val index: Int,
    ) : CorrectTransactionVersionViolation {
        override val reasonCode: String? = "matched_unaffected_posting_must_be_preserved"
        override val fieldPath: String = "$.attempted_input.replacement_postings[$index]"
    }

    /** reason `explicit_confirmation_required` (reject-8: user confirmation missing or not true). */
    data object ExplicitConfirmationRequired : CorrectTransactionVersionViolation {
        override val reasonCode: String? = "explicit_confirmation_required"
        override val fieldPath: String = "$.attempted_input.explicit_confirmation"
    }

    /** reason `exact_decimal_string_required` (reject-9, first amount that is not an exact decimal string). */
    data class ExactDecimalStringRequired(
        val index: Int,
    ) : CorrectTransactionVersionViolation {
        override val reasonCode: String? = "exact_decimal_string_required"
        override val fieldPath: String = "$.attempted_input.replacement_postings[$index].amount"
    }

    /** reason `historical_facts_immutable` (reject-10: any `history_mutation` input). */
    data object HistoricalFactsImmutable : CorrectTransactionVersionViolation {
        override val reasonCode: String? = "historical_facts_immutable"
        override val fieldPath: String = "$.attempted_input.history_mutation"
    }

    /** Internal integrity only: facts of an old current posting are missing; never a frozen reason. */
    data object IncompleteOldPostingFacts : CorrectTransactionVersionViolation {
        override val reasonCode: String? = null
        override val fieldPath: String = ""
    }
}
