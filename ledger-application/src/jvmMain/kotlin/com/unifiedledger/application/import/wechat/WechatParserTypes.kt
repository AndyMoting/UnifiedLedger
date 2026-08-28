package com.unifiedledger.application.import.wechat

import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportRecordKind
import com.unifiedledger.application.ImportSourceFacts

/**
 * P4-03 parse result shapes (frozen spec sections 1.3, 2.4 and 5).
 *
 * Diagnostics use the frozen registry: D-097:1459 codes for input/container/structure
 * and fact-level semantics, SPINE_WEIXIN_* for the three provider-specific codes.
 * The safe location holds only {input_ref, record_ordinal, field_role}; raw values,
 * header cells, whole rows and metadata-area content never appear here.
 */
enum class WechatBatchOutcome { COMPLETE, PARTIAL, REJECTED }

data class WechatDiagnostic(
    val code: String,
    val severity: String,
    val scope: String,
    val inputRef: String,
    val recordOrdinal: Int?,
    val fieldRole: String?,
)

sealed interface WechatRowResult {
    val recordOrdinal: Int
    val diagnostics: List<WechatDiagnostic>

    data class Accepted(
        override val recordOrdinal: Int,
        val recordKind: ImportRecordKind,
        val facts: ImportSourceFacts,
        val completeness: ImportCompleteness,
        override val diagnostics: List<WechatDiagnostic>,
    ) : WechatRowResult

    /** Rejected rows carry exactly one diagnostic and produce no record (zero write). */
    data class Rejected(
        override val recordOrdinal: Int,
        override val diagnostics: List<WechatDiagnostic>,
    ) : WechatRowResult
}

data class WechatBatchResult(
    val outcome: WechatBatchOutcome,
    val rows: List<WechatRowResult>,
    /** Non-null iff outcome == REJECTED (container/input/structure fatal). */
    val diagnostic: WechatDiagnostic?,
)

object WechatDiagnostics {
    fun unsupportedInput(inputRef: String): WechatDiagnostic = WechatDiagnostic("INPUT_UNSUPPORTED", "fatal", "input", inputRef, null, null)

    fun unsafeOrOverLimit(inputRef: String): WechatDiagnostic = WechatDiagnostic("INPUT_UNSAFE_OR_OVER_LIMIT", "fatal", "input", inputRef, null, null)

    fun unsafeOrOverLimitContainer(inputRef: String): WechatDiagnostic = WechatDiagnostic("INPUT_UNSAFE_OR_OVER_LIMIT", "fatal", "container", inputRef, null, null)

    fun decodeFailed(inputRef: String): WechatDiagnostic = WechatDiagnostic("INPUT_DECODE_FAILED", "fatal", "input", inputRef, null, null)

    fun structureMismatchHeader(inputRef: String): WechatDiagnostic = WechatDiagnostic("STRUCTURE_MISMATCH", "fatal", "structure", inputRef, null, null)

    fun structureMismatchRecord(
        inputRef: String,
        ordinal: Int,
    ): WechatDiagnostic = WechatDiagnostic("STRUCTURE_MISMATCH", "fatal", "record", inputRef, ordinal, null)

    fun unsupportedTxType(
        inputRef: String,
        ordinal: Int,
    ): WechatDiagnostic = WechatDiagnostic("SPINE_WEIXIN_UNSUPPORTED_TX_TYPE", "unsupported", "record", inputRef, ordinal, null)

    fun refundUnsupported(
        inputRef: String,
        ordinal: Int,
    ): WechatDiagnostic = WechatDiagnostic("SPINE_WEIXIN_REFUND_UNSUPPORTED", "unsupported", "record", inputRef, ordinal, null)

    fun unknownToken(
        inputRef: String,
        ordinal: Int,
    ): WechatDiagnostic = WechatDiagnostic("SPINE_WEIXIN_UNKNOWN_TOKEN", "unsupported", "record", inputRef, ordinal, null)

    fun conflictingSourceFacts(
        inputRef: String,
        ordinal: Int,
    ): WechatDiagnostic = WechatDiagnostic("CONFLICTING_SOURCE_FACTS", "record_error", "record", inputRef, ordinal, null)

    fun fieldAmountInvalid(
        inputRef: String,
        ordinal: Int,
    ): WechatDiagnostic = WechatDiagnostic("FIELD_AMOUNT_INVALID", "record_error", "field", inputRef, ordinal, WechatSourceTokens.FIELD_ROLE_AMOUNT)

    fun fieldTimeInvalid(
        inputRef: String,
        ordinal: Int,
    ): WechatDiagnostic = WechatDiagnostic("FIELD_TIME_INVALID", "record_error", "field", inputRef, ordinal, WechatSourceTokens.FIELD_ROLE_OCCURRED_AT)

    fun requiredFactUnresolved(
        inputRef: String,
        ordinal: Int,
        fieldRole: String,
    ): WechatDiagnostic = WechatDiagnostic("REQUIRED_FACT_UNRESOLVED", "incomplete", "field", inputRef, ordinal, fieldRole)

    /** Defensive registration (spec section 5): this batch's oracle never triggers it. */
    fun requiredFactMissing(
        inputRef: String,
        ordinal: Int,
        fieldRole: String,
    ): WechatDiagnostic = WechatDiagnostic("REQUIRED_FACT_MISSING", "incomplete", "field", inputRef, ordinal, fieldRole)
}
