package com.unifiedledger.application.import.alipay

import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportPaymentProfile
import com.unifiedledger.application.ImportRecordKind
import com.unifiedledger.application.ImportSourceFacts

/**
 * P4-05 parse result shapes (frozen spec sections 1.3, 2.5 and 4).
 *
 * Diagnostics use the frozen registry: D-097:1459 codes for input/container/structure
 * and fact-level semantics, SPINE_ALIPAY_* for the three provider-specific codes.
 * The safe location holds only {input_ref, record_ordinal, field_role}; raw values,
 * header cells, whole rows and metadata-area content never appear here.
 */
enum class AlipayBatchOutcome { COMPLETE, PARTIAL, REJECTED }

data class AlipayDiagnostic(
    val code: String,
    val severity: String,
    val scope: String,
    val inputRef: String,
    val recordOrdinal: Int?,
    val fieldRole: String?,
)

sealed interface AlipayRowResult {
    val recordOrdinal: Int
    val diagnostics: List<AlipayDiagnostic>

    data class Accepted(
        override val recordOrdinal: Int,
        val recordKind: ImportRecordKind,
        val facts: ImportSourceFacts,
        val completeness: ImportCompleteness,
        override val diagnostics: List<AlipayDiagnostic>,
        /** P4-06 slice 1: non-null exactly for v3 kinds; carries only normalized whitelist tokens. */
        val paymentProfile: ImportPaymentProfile? = null,
    ) : AlipayRowResult

    /** Rejected rows carry exactly one diagnostic and produce no record (zero write). */
    data class Rejected(
        override val recordOrdinal: Int,
        override val diagnostics: List<AlipayDiagnostic>,
    ) : AlipayRowResult
}

data class AlipayBatchResult(
    val outcome: AlipayBatchOutcome,
    val rows: List<AlipayRowResult>,
    /** Non-null iff outcome == REJECTED (container/input/structure fatal). */
    val diagnostic: AlipayDiagnostic?,
)

object AlipayDiagnostics {
    fun unsupportedInput(inputRef: String): AlipayDiagnostic = AlipayDiagnostic("INPUT_UNSUPPORTED", "fatal", "input", inputRef, null, null)

    fun unsafeOrOverLimit(inputRef: String): AlipayDiagnostic = AlipayDiagnostic("INPUT_UNSAFE_OR_OVER_LIMIT", "fatal", "input", inputRef, null, null)

    fun decodeFailed(inputRef: String): AlipayDiagnostic = AlipayDiagnostic("INPUT_DECODE_FAILED", "fatal", "input", inputRef, null, null)

    fun structureMismatchHeader(inputRef: String): AlipayDiagnostic = AlipayDiagnostic("STRUCTURE_MISMATCH", "fatal", "structure", inputRef, null, null)

    fun structureMismatchRecord(
        inputRef: String,
        ordinal: Int,
    ): AlipayDiagnostic = AlipayDiagnostic("STRUCTURE_MISMATCH", "fatal", "record", inputRef, ordinal, null)

    fun unsupportedTxType(
        inputRef: String,
        ordinal: Int,
    ): AlipayDiagnostic = AlipayDiagnostic("SPINE_ALIPAY_UNSUPPORTED_TX_TYPE", "unsupported", "record", inputRef, ordinal, null)

    fun refundUnsupported(
        inputRef: String,
        ordinal: Int,
    ): AlipayDiagnostic = AlipayDiagnostic("SPINE_ALIPAY_REFUND_UNSUPPORTED", "unsupported", "record", inputRef, ordinal, null)

    fun unknownToken(
        inputRef: String,
        ordinal: Int,
    ): AlipayDiagnostic = AlipayDiagnostic("SPINE_ALIPAY_UNKNOWN_TOKEN", "unsupported", "record", inputRef, ordinal, null)

    /** P4-06 slice 1 (D-107 section 2.4): any non-whitelist payment-leg token. */
    fun unknownPaymentLeg(
        inputRef: String,
        ordinal: Int,
    ): AlipayDiagnostic = AlipayDiagnostic("SPINE_ALIPAY_UNKNOWN_PAYMENT_LEG", "unsupported", "record", inputRef, ordinal, null)

    /** P4-06 slice 1: slice-2 fail-closed asset+credit mixed leg with an 支出 direction. */
    fun mixedPaymentUnsupported(
        inputRef: String,
        ordinal: Int,
    ): AlipayDiagnostic = AlipayDiagnostic("SPINE_ALIPAY_MIXED_PAYMENT_UNSUPPORTED", "unsupported", "record", inputRef, ordinal, null)

    /** P4-06 slice 1: defensive credit leg with an 收入 direction (no anchor). */
    fun creditIncomeUnsupported(
        inputRef: String,
        ordinal: Int,
    ): AlipayDiagnostic = AlipayDiagnostic("SPINE_ALIPAY_CREDIT_INCOME_UNSUPPORTED", "unsupported", "record", inputRef, ordinal, null)

    fun fieldAmountInvalid(
        inputRef: String,
        ordinal: Int,
    ): AlipayDiagnostic = AlipayDiagnostic("FIELD_AMOUNT_INVALID", "record_error", "field", inputRef, ordinal, AlipaySourceTokens.FIELD_ROLE_AMOUNT)

    fun fieldTimeInvalid(
        inputRef: String,
        ordinal: Int,
    ): AlipayDiagnostic = AlipayDiagnostic("FIELD_TIME_INVALID", "record_error", "field", inputRef, ordinal, AlipaySourceTokens.FIELD_ROLE_OCCURRED_AT)

    fun requiredFactUnresolved(
        inputRef: String,
        ordinal: Int,
        fieldRole: String,
    ): AlipayDiagnostic = AlipayDiagnostic("REQUIRED_FACT_UNRESOLVED", "incomplete", "field", inputRef, ordinal, fieldRole)

    /** Defensive registration (spec section 4): this batch's oracle never triggers it. */
    fun requiredFactMissing(
        inputRef: String,
        ordinal: Int,
        fieldRole: String,
    ): AlipayDiagnostic = AlipayDiagnostic("REQUIRED_FACT_MISSING", "incomplete", "field", inputRef, ordinal, fieldRole)
}
