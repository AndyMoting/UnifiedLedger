package com.unifiedledger.application.import.cmb

import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportRecordKind
import com.unifiedledger.application.ImportSourceFacts

/**
 * BP-01 CMB parse result shapes (spec sections 2.4 and 4).
 *
 * Diagnostics use the frozen registry: D-097:1459 codes for input/container/structure
 * and fact-level semantics, SPINE_CMB_* for the three provider-specific codes, and
 * SPINE_BANK_BALANCE_* for the two non-blocking balance-mirror codes registered in the
 * spec section 2.4 table. The safe location holds only {input_ref, record_ordinal,
 * field_role}; raw values, header cells, whole rows and comment-block content never
 * appear here.
 */
enum class CmbBatchOutcome { COMPLETE, PARTIAL, REJECTED }

data class CmbDiagnostic(
    val code: String,
    val severity: String,
    val scope: String,
    val inputRef: String,
    val recordOrdinal: Int?,
    val fieldRole: String?,
)

sealed interface CmbRowResult {
    val recordOrdinal: Int
    val diagnostics: List<CmbDiagnostic>

    data class Accepted(
        override val recordOrdinal: Int,
        val recordKind: ImportRecordKind,
        val facts: ImportSourceFacts,
        val completeness: ImportCompleteness,
        override val diagnostics: List<CmbDiagnostic>,
    ) : CmbRowResult

    /** Rejected rows carry exactly one diagnostic and produce no record (zero write). */
    data class Rejected(
        override val recordOrdinal: Int,
        override val diagnostics: List<CmbDiagnostic>,
    ) : CmbRowResult
}

data class CmbBatchResult(
    val outcome: CmbBatchOutcome,
    val rows: List<CmbRowResult>,
    /** Non-null iff outcome == REJECTED (container/input/structure fatal). */
    val diagnostic: CmbDiagnostic?,
)

object CmbDiagnostics {
    fun unsafeOrOverLimit(inputRef: String): CmbDiagnostic = CmbDiagnostic("INPUT_UNSAFE_OR_OVER_LIMIT", "fatal", "input", inputRef, null, null)

    fun decodeFailed(inputRef: String): CmbDiagnostic = CmbDiagnostic("INPUT_DECODE_FAILED", "fatal", "input", inputRef, null, null)

    fun structureMismatchHeader(inputRef: String): CmbDiagnostic = CmbDiagnostic("STRUCTURE_MISMATCH", "fatal", "structure", inputRef, null, null)

    fun structureMismatchRecord(
        inputRef: String,
        ordinal: Int,
    ): CmbDiagnostic = CmbDiagnostic("STRUCTURE_MISMATCH", "fatal", "record", inputRef, ordinal, null)

    fun unsupportedTxType(
        inputRef: String,
        ordinal: Int,
    ): CmbDiagnostic = CmbDiagnostic("SPINE_CMB_UNSUPPORTED_TX_TYPE", "unsupported", "record", inputRef, ordinal, null)

    fun refundUnsupported(
        inputRef: String,
        ordinal: Int,
    ): CmbDiagnostic = CmbDiagnostic("SPINE_CMB_REFUND_UNSUPPORTED", "unsupported", "record", inputRef, ordinal, null)

    fun unknownToken(
        inputRef: String,
        ordinal: Int,
    ): CmbDiagnostic = CmbDiagnostic("SPINE_CMB_UNKNOWN_TOKEN", "unsupported", "record", inputRef, ordinal, null)

    /** Non-blocking balance-mirror diagnostic (spec section 2.3/2.4); record still produced. */
    fun balanceContinuity(
        inputRef: String,
        ordinal: Int,
    ): CmbDiagnostic = CmbDiagnostic("SPINE_BANK_BALANCE_CONTINUITY", "note", "record", inputRef, ordinal, null)

    /** Non-blocking balance-mirror diagnostic (spec section 2.3/2.4); record still produced. */
    fun balanceMissing(
        inputRef: String,
        ordinal: Int,
    ): CmbDiagnostic = CmbDiagnostic("SPINE_BANK_BALANCE_MISSING", "note", "field", inputRef, ordinal, CmbSourceTokens.FIELD_ROLE_BALANCE)

    fun fieldAmountInvalid(
        inputRef: String,
        ordinal: Int,
    ): CmbDiagnostic = CmbDiagnostic("FIELD_AMOUNT_INVALID", "record_error", "field", inputRef, ordinal, CmbSourceTokens.FIELD_ROLE_AMOUNT)

    fun fieldTimeInvalid(
        inputRef: String,
        ordinal: Int,
    ): CmbDiagnostic = CmbDiagnostic("FIELD_TIME_INVALID", "record_error", "field", inputRef, ordinal, CmbSourceTokens.FIELD_ROLE_OCCURRED_AT)

    fun conflictingSourceFacts(
        inputRef: String,
        ordinal: Int,
    ): CmbDiagnostic = CmbDiagnostic("CONFLICTING_SOURCE_FACTS", "record_error", "record", inputRef, ordinal, null)

    fun requiredFactUnresolved(
        inputRef: String,
        ordinal: Int,
        fieldRole: String,
    ): CmbDiagnostic = CmbDiagnostic("REQUIRED_FACT_UNRESOLVED", "incomplete", "field", inputRef, ordinal, fieldRole)

    /** Defensive registration (spec section 2.4); this batch's oracle never triggers it. */
    fun requiredFactMissing(
        inputRef: String,
        ordinal: Int,
        fieldRole: String,
    ): CmbDiagnostic = CmbDiagnostic("REQUIRED_FACT_MISSING", "incomplete", "field", inputRef, ordinal, fieldRole)
}
