package com.unifiedledger.application.import.alipay

import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportRecordKind
import com.unifiedledger.application.ImportSourceFacts
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.DateTimeException
import java.time.LocalDateTime

/**
 * P4-05 Alipay CSV parser (frozen spec sections 2-4).
 *
 * Deterministic pure function over an in-memory byte stream: no file/network I/O, no
 * Clock, no randomness, no path dependencies. Input = csv bytes + opaque synthetic
 * input ref; output = per-row Accepted(facts)/Rejected(diagnostic) results plus the
 * batch outcome. Decoding is strict UTF-8 first with a GB18030 fallback; the header is
 * frozen at 0-based line 23 with a byte-exact 13-field match (no line scanning, no
 * drift tolerance). The metadata area (lines 0-22) is never read. Money is derived
 * only from the exact decimal text of the amount field (never through binary floating
 * point). Container/input/structure diagnostics reuse the D-097:1459 codes;
 * SPINE_ALIPAY_* are the three provider-specific codes.
 */
object AlipayCsvParser {

    fun parse(inputRef: String, bytes: ByteArray): AlipayBatchResult {
        bytePrecheck(inputRef, bytes)?.let { return rejected(it) }
        val text = decode(bytes) ?: return rejected(AlipayDiagnostics.decodeFailed(inputRef))
        val lines = text.split("\n")
        val physicalLineCount = lines.size - if (text.endsWith("\n")) 1 else 0
        if (physicalLineCount <= AlipaySourceTokens.HEADER_ROW_INDEX) {
            return rejected(AlipayDiagnostics.structureMismatchHeader(inputRef))
        }
        headerViolation(inputRef, lines[AlipaySourceTokens.HEADER_ROW_INDEX])?.let { return rejected(it) }
        if (physicalLineCount - AlipaySourceTokens.FIRST_DATA_ROW_INDEX > AlipaySourceTokens.MAX_DATA_ROWS) {
            return rejected(AlipayDiagnostics.unsafeOrOverLimit(inputRef))
        }
        val rows = mutableListOf<AlipayRowResult>()
        for (lineIndex in AlipaySourceTokens.FIRST_DATA_ROW_INDEX until lines.size) {
            val ordinal = lineIndex - AlipaySourceTokens.FIRST_DATA_ROW_INDEX
            val line = lines[lineIndex]
            if (line.replace("\r", "").isEmpty()) continue // fully empty row / EOF blank: no record, no renumbering
            if (line.contains('\r')) {
                rows += AlipayRowResult.Rejected(
                    ordinal, listOf(AlipayDiagnostics.structureMismatchRecord(inputRef, ordinal)),
                )
                continue
            }
            rows += parseDataRow(inputRef, ordinal, line)
        }
        val diagnostics = rows.flatMap { it.diagnostics }
        val outcome = if (diagnostics.isEmpty()) AlipayBatchOutcome.COMPLETE else AlipayBatchOutcome.PARTIAL
        return AlipayBatchResult(outcome, rows, null)
    }

    // Frozen byte pre-check order (spec section 2.1): empty, over-limit, PK zip, OLE2.
    // Encrypted-zip unpacking and the password channel belong to the platform adapter
    // layer; the parser never opens containers.
    private fun bytePrecheck(inputRef: String, bytes: ByteArray): AlipayDiagnostic? {
        if (bytes.isEmpty()) return AlipayDiagnostics.decodeFailed(inputRef)
        if (bytes.size > AlipaySourceTokens.MAX_INPUT_BYTES) return AlipayDiagnostics.unsafeOrOverLimit(inputRef)
        if (isZipContainer(bytes) || isOle2Container(bytes)) return AlipayDiagnostics.unsupportedInput(inputRef)
        return null
    }

    // Frozen decode order (spec section 2.1): strict UTF-8 first, GB18030 fallback.
    // No BOM tolerance: a U+FEFF prefix simply fails the byte-exact header match.
    private fun decode(bytes: ByteArray): String? {
        strictDecode(Charsets.UTF_8, bytes)?.let { return it }
        return strictDecode(GB18030, bytes)
    }

    private fun strictDecode(charset: Charset, bytes: ByteArray): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (failure: CharacterCodingException) {
        null
    }

    // Header: frozen 0-based line 23, exactly 13 comma-separated fields (12 frozen
    // tokens in fixed order + empty trailing-comma field), byte-exact. No line-number
    // scanning and no drift tolerance (D-099:1536).
    private fun headerViolation(inputRef: String, headerLine: String): AlipayDiagnostic? {
        if (headerLine.contains('\r')) return AlipayDiagnostics.structureMismatchHeader(inputRef)
        val fields = headerLine.split(",")
        if (fields.size != AlipaySourceTokens.FIELD_COUNT) return AlipayDiagnostics.structureMismatchHeader(inputRef)
        if (fields.last().isNotEmpty()) return AlipayDiagnostics.structureMismatchHeader(inputRef)
        AlipaySourceTokens.HEADER_TOKENS.forEachIndexed { index, token ->
            if (fields[index] != token) return AlipayDiagnostics.structureMismatchHeader(inputRef)
        }
        return null
    }

    // Frozen judgment order (spec section 3.3 + RL-04 design section 2.3): refund marker
    // first, then the 投资理财 余额宝 transfer routing branch (RL-04, new), then rejected
    // category set, then unknown category, then the section 2.4 fact mapping. The yuebao
    // branch routes only 余额宝-* frozen subtypes; all other 投资理财 rows stay fail-closed.
    private fun parseDataRow(inputRef: String, ordinal: Int, line: String): AlipayRowResult {
        val fields = line.split(",")
        if (fields.size != AlipaySourceTokens.FIELD_COUNT || fields.last().isNotEmpty() || !tabShapeValid(fields)) {
            return AlipayRowResult.Rejected(
                ordinal, listOf(AlipayDiagnostics.structureMismatchRecord(inputRef, ordinal)),
            )
        }
        val typeToken = fields[1]
        val statusRaw = fields[8]
        // Judgment order 1: refund marker in category or status.
        if (typeToken.contains(AlipaySourceTokens.REFUND_MARKER) ||
            statusRaw.contains(AlipaySourceTokens.REFUND_MARKER)
        ) {
            return AlipayRowResult.Rejected(ordinal, listOf(AlipayDiagnostics.refundUnsupported(inputRef, ordinal)))
        }
        // Judgment order 2: 投资理财 -> 余额宝 transfer routing branch (RL-04, frozen).
        if (typeToken == AlipaySourceTokens.INVESTMENT_CATEGORY) {
            return parseInvestmentRow(inputRef, ordinal, fields, statusRaw)
        }
        if (typeToken in AlipaySourceTokens.REJECTED_TX_TYPES) {
            return AlipayRowResult.Rejected(ordinal, listOf(AlipayDiagnostics.unsupportedTxType(inputRef, ordinal)))
        }
        if (typeToken !in AlipaySourceTokens.ACCEPTED_TX_TYPES) {
            return AlipayRowResult.Rejected(ordinal, listOf(AlipayDiagnostics.unknownToken(inputRef, ordinal)))
        }
        val occurredAt = parseTime(fields[0])
            ?: return AlipayRowResult.Rejected(ordinal, listOf(AlipayDiagnostics.fieldTimeInvalid(inputRef, ordinal)))
        val amountMinor = parseAmount(fields[6])
            ?: return AlipayRowResult.Rejected(ordinal, listOf(AlipayDiagnostics.fieldAmountInvalid(inputRef, ordinal)))
        val directionRaw = fields[5]
        val directionMapped = AlipaySourceTokens.DIRECTION_TOKEN_MAP[directionRaw]
        val statusMapped = AlipaySourceTokens.STATUS_TOKEN_MAP[statusRaw]
        val diagnostics = mutableListOf<AlipayDiagnostic>()
        if (directionMapped == null) {
            diagnostics += AlipayDiagnostics.requiredFactUnresolved(
                inputRef, ordinal, AlipaySourceTokens.FIELD_ROLE_DIRECTION,
            )
        }
        if (statusMapped == null) {
            diagnostics += AlipayDiagnostics.requiredFactUnresolved(
                inputRef, ordinal, AlipaySourceTokens.FIELD_ROLE_STATUS,
            )
        }
        val completeness = if (diagnostics.isEmpty()) ImportCompleteness.VALID_COMPLETE else ImportCompleteness.VALID_INCOMPLETE
        val facts = ImportSourceFacts(
            amountMinor = amountMinor,
            currencyCode = AlipaySourceTokens.CURRENCY_CNY,
            currencyPrecision = AlipaySourceTokens.AMOUNT_PRECISION,
            occurredAt = occurredAt,
            directionToken = directionMapped ?: directionRaw,
            statusToken = statusMapped ?: statusRaw.ifEmpty { null },
        )
        return AlipayRowResult.Accepted(ordinal, ImportRecordKind.ORDINARY_FLOW_SOURCE, facts, completeness, diagnostics)
    }

    // Judgment order 2 (RL-04 frozen branch): 商品说明 (field 4) is read exactly here and only
    // for 投资理财 rows (abstract subtype token, exact match; never persisted; provider DTO
    // zero). The 收/支 column (field 5) is not read by this branch: direction derives only
    // from the frozen subtype mapping. Route branches:
    //   (2a) frozen subtype + 交易成功 -> accepted TRANSFER_FLOW_SOURCE (contract_version 2),
    //        direction by subtype map, status settled, valid_complete.
    //   (2b) frozen subtype + non-success status -> valid_incomplete: status raw preserved +
    //        unresolved; transfer_flow_source; amount/time/currency/direction stay reliable.
    //   (2c) any other subtype (empty/unknown, incl. the 余额宝-单次转入 / 余额宝-转出到银行卡 /
    //        余额宝-收益发放 registered families) -> fail-closed SPINE_ALIPAY_UNKNOWN_TOKEN.
    private fun parseInvestmentRow(
        inputRef: String,
        ordinal: Int,
        fields: List<String>,
        statusRaw: String,
    ): AlipayRowResult {
        val subtype = fields[4]
        // Single source of truth for routing membership (spec §2.3 2c): the frozen subtype
        // set is the member predicate; the direction map only supplies the direction for a
        // routed row. A subtype in the set but missing from the map fails fast (getValue).
        if (subtype !in AlipaySourceTokens.YUEBAO_TRANSFER_SUBTYPES) {
            return AlipayRowResult.Rejected(ordinal, listOf(AlipayDiagnostics.unknownToken(inputRef, ordinal)))
        }
        val directionMapped = AlipaySourceTokens.YUEBAO_SUBTYPE_DIRECTION_MAP.getValue(subtype)
        val occurredAt = parseTime(fields[0])
            ?: return AlipayRowResult.Rejected(ordinal, listOf(AlipayDiagnostics.fieldTimeInvalid(inputRef, ordinal)))
        val amountMinor = parseAmount(fields[6])
            ?: return AlipayRowResult.Rejected(ordinal, listOf(AlipayDiagnostics.fieldAmountInvalid(inputRef, ordinal)))
        val statusMapped = AlipaySourceTokens.STATUS_TOKEN_MAP[statusRaw]
        if (statusMapped == null) {
            val facts = ImportSourceFacts(
                amountMinor = amountMinor,
                currencyCode = AlipaySourceTokens.CURRENCY_CNY,
                currencyPrecision = AlipaySourceTokens.AMOUNT_PRECISION,
                occurredAt = occurredAt,
                directionToken = directionMapped,
                statusToken = statusRaw.ifEmpty { null },
            )
            return AlipayRowResult.Accepted(
                ordinal, ImportRecordKind.TRANSFER_FLOW_SOURCE, facts, ImportCompleteness.VALID_INCOMPLETE,
                listOf(AlipayDiagnostics.requiredFactUnresolved(inputRef, ordinal, AlipaySourceTokens.FIELD_ROLE_STATUS)),
            )
        }
        val facts = ImportSourceFacts(
            amountMinor = amountMinor,
            currencyCode = AlipaySourceTokens.CURRENCY_CNY,
            currencyPrecision = AlipaySourceTokens.AMOUNT_PRECISION,
            occurredAt = occurredAt,
            directionToken = directionMapped,
            statusToken = statusMapped,
        )
        return AlipayRowResult.Accepted(
            ordinal, ImportRecordKind.TRANSFER_FLOW_SOURCE, facts, ImportCompleteness.VALID_COMPLETE, emptyList(),
        )
    }

    // Frozen tab invariant (spec section 2.3): field 9 (交易订单号) = non-empty value +
    // single trailing tab; field 10 (商家订单号) = non-empty value + single trailing tab,
    // or completely empty; every other field carries no tab. The order-number values
    // are shape-checked only and never materialized into output, diagnostics or
    // persistence (zero provider DTO).
    private fun tabShapeValid(fields: List<String>): Boolean {
        for (index in intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 11)) {
            if (fields[index].contains('\t')) return false
        }
        if (!trailingTabValue(fields[9], allowEmpty = false)) return false
        return trailingTabValue(fields[10], allowEmpty = true)
    }

    private fun trailingTabValue(field: String, allowEmpty: Boolean): Boolean {
        if (field.isEmpty()) return allowEmpty
        return field.length >= 2 && field.endsWith('\t') && !field.dropLast(1).contains('\t')
    }

    // Time: frozen shape YYYY-MM-DD HH:mm:ss, strict calendar validation, frozen
    // +08:00 offset, second granularity, deterministic byte-equal output text. No
    // Clock, no timezone read from any region of the file.
    private fun parseTime(raw: String): String? {
        if (!TIME_SHAPE.matches(raw)) return null
        val isoLocal = raw.replace(' ', 'T')
        return try {
            LocalDateTime.parse(isoLocal)
            isoLocal + AlipaySourceTokens.UTC_OFFSET
        } catch (failure: DateTimeException) {
            null
        }
    }

    // Amount: frozen shape \d+\.\d{2} (non-negative, exactly two decimals; the signed
    // community domain is fail-closed to non-negative). Exact decimal arithmetic only,
    // never binary floating point; currency precision is the frozen constant 2.
    private fun parseAmount(raw: String): Long? {
        if (!AMOUNT_SHAPE.matches(raw)) return null
        return try {
            BigDecimal(raw).movePointRight(AlipaySourceTokens.AMOUNT_PRECISION).longValueExact()
        } catch (failure: ArithmeticException) {
            null
        } catch (failure: NumberFormatException) {
            null
        }
    }

    private fun rejected(diagnostic: AlipayDiagnostic): AlipayBatchResult =
        AlipayBatchResult(AlipayBatchOutcome.REJECTED, emptyList(), diagnostic)

    private fun isZipContainer(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
            ((bytes[2] == 3.toByte() && bytes[3] == 4.toByte()) ||
                (bytes[2] == 5.toByte() && bytes[3] == 6.toByte()) ||
                (bytes[2] == 7.toByte() && bytes[3] == 8.toByte()))

    private fun isOle2Container(bytes: ByteArray): Boolean =
        bytes.size >= 8 &&
            bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte() && bytes[2] == 0x11.toByte() &&
            bytes[3] == 0xE0.toByte() && bytes[4] == 0xA1.toByte() && bytes[5] == 0xB1.toByte() &&
            bytes[6] == 0x1A.toByte() && bytes[7] == 0xE1.toByte()

    private val TIME_SHAPE = Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")
    private val AMOUNT_SHAPE = Regex("\\d+\\.\\d{2}")

    private val GB18030: Charset = Charset.forName("GB18030")
}
