package com.unifiedledger.domain

/**
 * D-085 RG-11 field-level paths of the frozen direct-v2 rejection output. Each value mirrors
 * the `field_path` suffix of `golden/rules/rg-11.json` (operation-reject-*, e.g.
 * `$.attempted_input.amount`) so the application layer can recompute stable rejection output
 * without remapping.
 */
enum class PeriodicAllocationField(val jsonName: String) {
    AMOUNT("amount"),
    CURRENCY("currency"),
    ANCHOR("anchor"),
    INSTALLMENT_ID("installment_id"),
    RECOGNIZED_THROUGH("recognized_through"),
    REMAINING_INSTALLMENT_COUNT("remaining_installment_count"),
    INSTALLMENT_COUNT("installment_count"),
    SCHEDULE_ID("schedule_id"),
    REVISION_ID("revision_id"),
    REQUEST_ID("request_id"),
    ;

    /** JSON pointer path of the attempted input field, e.g. `$.attempted_input.amount`. */
    fun attemptedInputPath(): String = "$.attempted_input.$jsonName"
}

/**
 * D-085 RG-11 periodic allocation domain violations, modelled after [LendingViolation].
 *
 * The nine frozen rejection reasons of `golden/rules/rg-11.json` (operation-reject-*) map
 * one-to-one through [reasonCode] and [field] to the closed `attempted_input` field paths
 * recomputed by the validator. The remaining members guard entity-internal integrity only and
 * carry `reasonCode == null`; they never produce a frozen rejection reason by themselves.
 */
sealed interface PeriodicAllocationViolation : DomainViolation {
    val field: PeriodicAllocationField
    val reasonCode: String?

    /** reason `exact_decimal_string_required` (operation-reject-malformed-amount). */
    data class ExactDecimalStringRequired(
        override val field: PeriodicAllocationField = PeriodicAllocationField.AMOUNT,
    ) : PeriodicAllocationViolation {
        override val reasonCode: String? = "exact_decimal_string_required"
    }

    /** reason `must_be_positive` (operation-reject-zero-amount / -negative-amount). */
    data class MustBePositive(
        override val field: PeriodicAllocationField = PeriodicAllocationField.AMOUNT,
    ) : PeriodicAllocationViolation {
        override val reasonCode: String? = "must_be_positive"
    }

    /** reason `unsupported_currency` (operation-reject-unsupported-currency). */
    data class UnsupportedCurrency(
        override val field: PeriodicAllocationField = PeriodicAllocationField.CURRENCY,
    ) : PeriodicAllocationViolation {
        override val reasonCode: String? = "unsupported_currency"
    }

    /** reason `currency_mismatch` (operation-reject-mismatched-currency). */
    data class CurrencyMismatch(
        override val field: PeriodicAllocationField = PeriodicAllocationField.CURRENCY,
    ) : PeriodicAllocationViolation {
        override val reasonCode: String? = "currency_mismatch"
    }

    /** reason `invalid_anchor` (operation-reject-invalid-anchor, e.g. day_of_month day out of 1..28). */
    data class InvalidAnchor(
        override val field: PeriodicAllocationField = PeriodicAllocationField.ANCHOR,
    ) : PeriodicAllocationViolation {
        override val reasonCode: String? = "invalid_anchor"
    }

    /** reason `installment_not_pending` (operation-reject-already-recognized). */
    data class InstallmentNotPending(
        override val field: PeriodicAllocationField = PeriodicAllocationField.INSTALLMENT_ID,
    ) : PeriodicAllocationViolation {
        override val reasonCode: String? = "installment_not_pending"
    }

    /** reason `exceeds_remaining_prepaid` (operation-reject-exceeds-prepaid). */
    data class ExceedsRemainingPrepaid(
        override val field: PeriodicAllocationField = PeriodicAllocationField.AMOUNT,
    ) : PeriodicAllocationViolation {
        override val reasonCode: String? = "exceeds_remaining_prepaid"
    }

    /** reason `invalid_revision_boundary` (operation-reject-invalid-boundary). */
    data class InvalidRevisionBoundary(
        override val field: PeriodicAllocationField = PeriodicAllocationField.RECOGNIZED_THROUGH,
    ) : PeriodicAllocationViolation {
        override val reasonCode: String? = "invalid_revision_boundary"
    }

    /** reason `invalid_installment_count` (operation-reject-invalid-count). */
    data class InvalidInstallmentCount(
        override val field: PeriodicAllocationField = PeriodicAllocationField.REMAINING_INSTALLMENT_COUNT,
    ) : PeriodicAllocationViolation {
        override val reasonCode: String? = "invalid_installment_count"
    }

    /** A required identity (entity id, transaction id, account id, category id) is blank or duplicated. */
    data class IdentityRequired(
        override val field: PeriodicAllocationField = PeriodicAllocationField.REQUEST_ID,
    ) : PeriodicAllocationViolation {
        override val reasonCode: String? = null
    }

    /** The referenced schedule does not exist or does not own the referenced payload. */
    data class UnknownSchedule(
        override val field: PeriodicAllocationField = PeriodicAllocationField.SCHEDULE_ID,
    ) : PeriodicAllocationViolation {
        override val reasonCode: String? = null
    }

    /** The referenced installment does not exist or belongs to another schedule. */
    data class UnknownInstallment(
        override val field: PeriodicAllocationField = PeriodicAllocationField.INSTALLMENT_ID,
    ) : PeriodicAllocationViolation {
        override val reasonCode: String? = null
    }

    /** Revision numbering or installment ids violate the append-only rule (never overwrite, never reuse). */
    data class RevisionMustBeAppendOnly(
        override val field: PeriodicAllocationField = PeriodicAllocationField.REVISION_ID,
    ) : PeriodicAllocationViolation {
        override val reasonCode: String? = null
    }

    /** `remaining_amount` does not equal total minus all previously recognized amounts. */
    data class RemainingAmountMismatch(
        override val field: PeriodicAllocationField = PeriodicAllocationField.AMOUNT,
    ) : PeriodicAllocationViolation {
        override val reasonCode: String? = null
    }

    /** The requested recognition amount does not equal the exact installment amount. */
    data class AmountMustMatchInstallment(
        override val field: PeriodicAllocationField = PeriodicAllocationField.AMOUNT,
    ) : PeriodicAllocationViolation {
        override val reasonCode: String? = null
    }
}
