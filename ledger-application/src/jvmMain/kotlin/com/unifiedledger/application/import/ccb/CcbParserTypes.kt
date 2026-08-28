package com.unifiedledger.application.import.ccb

import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportRecordKind
import com.unifiedledger.application.ImportSourceFacts

/**
 * BP-01 CCB parse result shapes (spec sections 2.4 and 4).
 *
 * Diagnostics use the frozen registry: D-097:1459 codes for input/container/structure
 * and fact-level semantics, SPINE_CCB_* for the three provider-specific codes, and
 * SPINE_BANK_BALANCE_* for the two non-blocking balance-mirror codes registered in the
 * spec section 2.4 table. The safe location holds only {input_ref, record_ordinal,
 * field_role}; raw values, header cells, whole rows and title-area content never
 * appear here.
 */
enum class CcbBatchOutcome { COMPLETE, PARTIAL, REJECTED }

data class CcbDiagnostic(
    val code: String,
    val severity: String,
    val scope: String,
    val inputRef: String,
    val recordOrdinal: Int?,
    val fieldRole: String?,
)

sealed interface CcbRowResult {
    val recordOrdinal: Int
    val diagnostics: List<CcbDiagnostic>

    data class Accepted(
        override val recordOrdinal: Int,
        val recordKind: ImportRecordKind,
        val facts: ImportSourceFacts,
        val completeness: ImportCompleteness,
        override val diagnostics: List<CcbDiagnostic>,
    ) : CcbRowResult

    /** Rejected rows carry exactly one diagnostic and produce no record (zero write). */
    data class Rejected(
        override val recordOrdinal: Int,
        override val diagnostics: List<CcbDiagnostic>,
    ) : CcbRowResult
}

data class CcbBatchResult(
    val outcome: CcbBatchOutcome,
    val rows: List<CcbRowResult>,
    /** Non-null iff outcome == REJECTED (container/input/structure fatal). */
    val diagnostic: CcbDiagnostic?,
)

object CcbDiagnostics {
    fun unsafeOrOverLimit(inputRef: String): CcbDiagnostic = CcbDiagnostic("INPUT_UNSAFE_OR_OVER_LIMIT", "fatal", "input", inputRef, null, null)

    fun decodeFailed(inputRef: String): CcbDiagnostic = CcbDiagnostic("INPUT_DECODE_FAILED", "fatal", "input", inputRef, null, null)

    fun structureMismatchHeader(inputRef: String): CcbDiagnostic = CcbDiagnostic("STRUCTURE_MISMATCH", "fatal", "structure", inputRef, null, null)

    fun structureMismatchRecord(
        inputRef: String,
        ordinal: Int,
    ): CcbDiagnostic = CcbDiagnostic("STRUCTURE_MISMATCH", "fatal", "record", inputRef, ordinal, null)

    fun unsupportedTxType(
        inputRef: String,
        ordinal: Int,
    ): CcbDiagnostic = CcbDiagnostic("SPINE_CCB_UNSUPPORTED_TX_TYPE", "unsupported", "record", inputRef, ordinal, null)

    fun refundUnsupported(
        inputRef: String,
        ordinal: Int,
    ): CcbDiagnostic = CcbDiagnostic("SPINE_CCB_REFUND_UNSUPPORTED", "unsupported", "record", inputRef, ordinal, null)

    fun unknownToken(
        inputRef: String,
        ordinal: Int,
    ): CcbDiagnostic = CcbDiagnostic("SPINE_CCB_UNKNOWN_TOKEN", "unsupported", "record", inputRef, ordinal, null)

    /** Non-blocking balance-mirror diagnostic (spec section 2.3/2.4); record still produced. */
    fun balanceContinuity(
        inputRef: String,
        ordinal: Int,
    ): CcbDiagnostic = CcbDiagnostic("SPINE_BANK_BALANCE_CONTINUITY", "note", "record", inputRef, ordinal, null)

    /** Non-blocking balance-mirror diagnostic (spec section 2.3/2.4); record still produced. */
    fun balanceMissing(
        inputRef: String,
        ordinal: Int,
    ): CcbDiagnostic = CcbDiagnostic("SPINE_BANK_BALANCE_MISSING", "note", "field", inputRef, ordinal, CcbSourceTokens.FIELD_ROLE_BALANCE)

    fun fieldAmountInvalid(
        inputRef: String,
        ordinal: Int,
    ): CcbDiagnostic = CcbDiagnostic("FIELD_AMOUNT_INVALID", "record_error", "field", inputRef, ordinal, CcbSourceTokens.FIELD_ROLE_AMOUNT)

    fun fieldTimeInvalid(
        inputRef: String,
        ordinal: Int,
    ): CcbDiagnostic = CcbDiagnostic("FIELD_TIME_INVALID", "record_error", "field", inputRef, ordinal, CcbSourceTokens.FIELD_ROLE_OCCURRED_AT)

    fun requiredFactUnresolved(
        inputRef: String,
        ordinal: Int,
        fieldRole: String,
    ): CcbDiagnostic = CcbDiagnostic("REQUIRED_FACT_UNRESOLVED", "incomplete", "field", inputRef, ordinal, fieldRole)

    /** Defensive registration (spec section 2.4); this batch's oracle never triggers it. */
    fun requiredFactMissing(
        inputRef: String,
        ordinal: Int,
        fieldRole: String,
    ): CcbDiagnostic = CcbDiagnostic("REQUIRED_FACT_MISSING", "incomplete", "field", inputRef, ordinal, fieldRole)
}
