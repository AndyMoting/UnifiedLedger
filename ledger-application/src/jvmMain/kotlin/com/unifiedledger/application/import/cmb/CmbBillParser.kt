package com.unifiedledger.application.import.cmb

import com.unifiedledger.application.IMPORT_FUNDING_RULE_LEGACY_SETTLED
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportFundingState
import com.unifiedledger.application.ImportRecordKind
import com.unifiedledger.application.ImportSourceFacts
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * BP-01 CMB online-banking CSV parser (frozen spec sections 2.1, 2.3, 2.4, 3.1, 3.3).
 *
 * Deterministic pure function over an in-memory byte stream: no file/network I/O, no
 * Clock, no randomness, no path dependencies. Input = csv bytes + opaque synthetic
 * input ref; output = per-row Accepted(facts)/Rejected(diagnostic) results plus the
 * batch outcome. Decoding is strict UTF-8 (a U+FEFF prefix is stripped); line
 * separators are CRLF. The comment block (0-based rows 0..5) and the empty row 6 are
 * never read; the header is frozen at 0-based row 7 with a byte-exact 7-field match
 * (no line scanning, no drift tolerance). Columns 0/1/6 carry a single leading tab
 * inside the quotes (stripped after parsing); columns 2/3/4/5 carry none. Money is
 * derived only from the exact decimal text of the amount field (never through binary
 * floating point).
 *
 * Balance mirror (spec section 2.3): each row's declared balance participates in a
 * descending-chain continuity assertion over the raw (pre-routing) rows; rejected
 * rows still participate, record_error rows do not (the chain re-anchors), and a
 * mismatch is a non-blocking SPINE_BANK_BALANCE_CONTINUITY note. Balance values never
 * enter the five source facts.
 */
object CmbBillParser {
    fun parse(
        inputRef: String,
        bytes: ByteArray,
    ): CmbBatchResult {
        bytePrecheck(inputRef, bytes)?.let { return rejected(it) }
        val text = decode(bytes) ?: return rejected(CmbDiagnostics.decodeFailed(inputRef))
        val content = if (text.startsWith(BOM_CHAR)) text.drop(1) else text
        val lines = content.split("\n")
        val physicalLineCount = lines.size - if (content.endsWith("\n")) 1 else 0
        if (physicalLineCount <= CmbSourceTokens.HEADER_ROW_INDEX) {
            return rejected(CmbDiagnostics.structureMismatchHeader(inputRef))
        }
        headerViolation(inputRef, stripTrailingCarriageReturn(lines[CmbSourceTokens.HEADER_ROW_INDEX]))?.let {
            return rejected(it)
        }
        if (physicalLineCount - CmbSourceTokens.FIRST_DATA_ROW_INDEX > CmbSourceTokens.MAX_DATA_ROWS) {
            return rejected(CmbDiagnostics.unsafeOrOverLimit(inputRef))
        }
        val rows = mutableListOf<CmbRowResult>()
        var previous: CmbBalanceObservation? = null
        for (lineIndex in CmbSourceTokens.FIRST_DATA_ROW_INDEX until lines.size) {
            val ordinal = lineIndex - CmbSourceTokens.FIRST_DATA_ROW_INDEX
            val line = stripTrailingCarriageReturn(lines[lineIndex])
            if (line.isEmpty()) continue // fully empty row / EOF blank: no record, no renumbering
            if (line.contains('\r')) {
                // Record-level structure violation: break the continuity chain (re-anchor).
                previous = null
                rows += CmbRowResult.Rejected(ordinal, listOf(CmbDiagnostics.structureMismatchRecord(inputRef, ordinal)))
                continue
            }
            val fields = parseCsvFields(line)
            if (fields == null) {
                previous = null
                rows += CmbRowResult.Rejected(ordinal, listOf(CmbDiagnostics.structureMismatchRecord(inputRef, ordinal)))
                continue
            }
            // Single-field block rows (`""` empty line and quoted `#` summary lines) are
            // skipped entirely, zero-read, without renumbering (spec section 2.1).
            if (isSkippableBlockRow(fields)) continue
            if (fields.size != CmbSourceTokens.FIELD_COUNT) {
                // Record-level structure violation: break the continuity chain (re-anchor).
                previous = null
                rows += CmbRowResult.Rejected(ordinal, listOf(CmbDiagnostics.structureMismatchRecord(inputRef, ordinal)))
                continue
            }
            val observation = observe(fields)
            val parsed = parseDataRow(inputRef, ordinal, fields)
            val result =
                if (observation != null && previous != null && parsed is CmbRowResult.Accepted) {
                    val expected = previous.balanceMinor + previous.expenseMinor - previous.incomeMinor
                    if (observation.balanceMinor != expected) {
                        parsed.copy(
                            diagnostics = parsed.diagnostics + CmbDiagnostics.balanceContinuity(inputRef, ordinal),
                        )
                    } else {
                        parsed
                    }
                } else {
                    parsed
                }
            // Chain base: the immediately previous physical data row. A non-participating
            // row (record_error / record-level structure violation) sets previous = null,
            // so the next valid row re-anchors without a check (spec section 2.3 item 3
            // and section 4.1 note: R16 re-anchors after R12-R15).
            previous = observation
            rows += result
        }
        val diagnostics = rows.flatMap { it.diagnostics }
        val outcome = if (diagnostics.isEmpty()) CmbBatchOutcome.COMPLETE else CmbBatchOutcome.PARTIAL
        return CmbBatchResult(outcome, rows, null)
    }

    // Frozen byte pre-check order (spec section 2.1): empty, over-limit.
    private fun bytePrecheck(
        inputRef: String,
        bytes: ByteArray,
    ): CmbDiagnostic? {
        if (bytes.isEmpty()) return CmbDiagnostics.decodeFailed(inputRef)
        if (bytes.size > CmbSourceTokens.MAX_INPUT_BYTES) return CmbDiagnostics.unsafeOrOverLimit(inputRef)
        return null
    }

    // Frozen decode (spec section 2.1): strict UTF-8 only, no fallback charset.
    private fun decode(bytes: ByteArray): String? =
        try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (failure: CharacterCodingException) {
            null
        }

    // Header: frozen 0-based row 7, exactly 7 quoted fields, byte-exact token match.
    private fun headerViolation(
        inputRef: String,
        headerLine: String,
    ): CmbDiagnostic? {
        val fields = parseCsvFields(headerLine)
        if (fields == null || fields.size != CmbSourceTokens.FIELD_COUNT) {
            return CmbDiagnostics.structureMismatchHeader(inputRef)
        }
        CmbSourceTokens.HEADER_TOKENS.forEachIndexed { index, token ->
            if (fields[index] != token) return CmbDiagnostics.structureMismatchHeader(inputRef)
        }
        return null
    }

    // RFC-4180 double-quote field parse. Escaped quotes (`""`) are supported for
    // robustness even though the frozen evidence never contains them; an unclosed
    // quote is malformed (structure violation).
    private fun parseCsvFields(line: String): List<String>? {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            if (inQuotes) {
                when {
                    char == '"' && index + 1 < line.length && line[index + 1] == '"' -> {
                        current.append('"')
                        index += 2
                    }
                    char == '"' -> {
                        inQuotes = false
                        index += 1
                    }
                    else -> {
                        current.append(char)
                        index += 1
                    }
                }
            } else {
                when (char) {
                    '"' -> {
                        inQuotes = true
                        index += 1
                    }
                    ',' -> {
                        fields += current.toString()
                        current.setLength(0)
                        index += 1
                    }
                    else -> {
                        current.append(char)
                        index += 1
                    }
                }
            }
        }
        if (inQuotes) return null
        fields += current.toString()
        return fields
    }

    private fun isSkippableBlockRow(fields: List<String>): Boolean = fields.size == 1 && (fields[0].isEmpty() || fields[0].startsWith("#"))

    /**
     * Raw-row balance observation used by the descending-chain continuity assertion
     * (spec section 2.3). Non-null only when the row can participate: valid balance,
     * valid time, and exactly one resolvable amount side.
     */
    private data class CmbBalanceObservation(
        val balanceMinor: Long,
        val incomeMinor: Long,
        val expenseMinor: Long,
    )

    private fun observe(fields: List<String>): CmbBalanceObservation? {
        val date = stripSingleLeadingTab(fields[0]) ?: return null
        val time = stripSingleLeadingTab(fields[1]) ?: return null
        if (parseDateTime(date, time) == null) return null
        val balanceMinor = parseNonNegativeTwoDecimal(fields[4]) ?: return null
        val income = fields[2]
        val expense = fields[3]
        if (income.isEmpty() == expense.isEmpty()) return null // both empty or both filled
        val incomeMinor = if (income.isEmpty()) 0L else parseNonNegativeTwoDecimal(income) ?: return null
        val expenseMinor = if (expense.isEmpty()) 0L else parseNonNegativeTwoDecimal(expense) ?: return null
        return CmbBalanceObservation(balanceMinor, incomeMinor, expenseMinor)
    }

    // Frozen judgment order (spec section 3.3): refund first, then (this batch has no
    // independent reject set), unknown, accept routing. Direction always comes from the
    // 收入/支出 columns (spec section 2.3), never from the type token.
    private fun parseDataRow(
        inputRef: String,
        ordinal: Int,
        fields: List<String>,
    ): CmbRowResult {
        val date = stripSingleLeadingTab(fields[0]) ?: return rejectedRecord(inputRef, ordinal)
        val time = stripSingleLeadingTab(fields[1]) ?: return rejectedRecord(inputRef, ordinal)
        if (fields[2].contains('\t') ||
            fields[3].contains('\t') ||
            fields[4].contains('\t') ||
            fields[5].contains('\t')
        ) {
            return rejectedRecord(inputRef, ordinal)
        }
        // Frozen tab quirk applies to a non-empty remark; the row-structure invariant
        // list (spec section 2.1) explicitly allows an empty remark (""), so only a
        // non-empty value without the leading tab violates the frozen shape.
        if (fields[6].isNotEmpty() && stripSingleLeadingTab(fields[6]) == null) return rejectedRecord(inputRef, ordinal)

        val typeToken = fields[5]
        // Judgment order 1: refund.
        if (typeToken.contains(CmbSourceTokens.REFUND_MARKER)) {
            return CmbRowResult.Rejected(ordinal, listOf(CmbDiagnostics.refundUnsupported(inputRef, ordinal)))
        }
        // Judgment order 3: unknown token fail-closed.
        val recordKind =
            when {
                typeToken in CmbSourceTokens.TRANSFER_TX_TYPES -> ImportRecordKind.TRANSFER_FLOW_SOURCE
                typeToken in CmbSourceTokens.ORDINARY_TX_TYPES -> ImportRecordKind.ORDINARY_FLOW_SOURCE
                else -> return CmbRowResult.Rejected(ordinal, listOf(CmbDiagnostics.unknownToken(inputRef, ordinal)))
            }

        val occurredAt =
            parseDateTime(date, time)
                ?: return CmbRowResult.Rejected(ordinal, listOf(CmbDiagnostics.fieldTimeInvalid(inputRef, ordinal)))

        val income = fields[2]
        val expense = fields[3]
        val amountMinor: Long
        val directionToken: String
        when {
            income.isEmpty() && expense.isEmpty() ->
                return CmbRowResult.Rejected(ordinal, listOf(CmbDiagnostics.fieldAmountInvalid(inputRef, ordinal)))
            income.isNotEmpty() && expense.isNotEmpty() ->
                return CmbRowResult.Rejected(ordinal, listOf(CmbDiagnostics.conflictingSourceFacts(inputRef, ordinal)))
            income.isNotEmpty() -> {
                amountMinor =
                    parseNonNegativeTwoDecimal(income)
                        ?: return CmbRowResult.Rejected(ordinal, listOf(CmbDiagnostics.fieldAmountInvalid(inputRef, ordinal)))
                directionToken = "in"
            }
            else -> {
                amountMinor =
                    parseNonNegativeTwoDecimal(expense)
                        ?: return CmbRowResult.Rejected(ordinal, listOf(CmbDiagnostics.fieldAmountInvalid(inputRef, ordinal)))
                directionToken = "out"
            }
        }

        // Balance column anomaly is a non-blocking note (spec section 2.3 item 4); the
        // record still produces valid_complete when every five-fact value is valid.
        val diagnostics = mutableListOf<CmbDiagnostic>()
        if (parseNonNegativeTwoDecimal(fields[4]) == null) {
            diagnostics += CmbDiagnostics.balanceMissing(inputRef, ordinal)
        }

        val facts =
            ImportSourceFacts(
                amountMinor = amountMinor,
                currencyCode = CmbSourceTokens.CURRENCY_CNY,
                currencyPrecision = CmbSourceTokens.AMOUNT_PRECISION,
                occurredAt = occurredAt,
                directionToken = directionToken,
                statusToken = CmbSourceTokens.STATUS_SETTLED,
                fundingState = ImportFundingState.SETTLED,
                fundingRuleId = IMPORT_FUNDING_RULE_LEGACY_SETTLED,
                fundingRuleVersion = 1,
            )
        return CmbRowResult.Accepted(ordinal, recordKind, facts, ImportCompleteness.VALID_COMPLETE, diagnostics)
    }

    private fun rejectedRecord(
        inputRef: String,
        ordinal: Int,
    ): CmbRowResult = CmbRowResult.Rejected(ordinal, listOf(CmbDiagnostics.structureMismatchRecord(inputRef, ordinal)))

    // Time: frozen shape YYYYMMDD + HH:MM:SS, strict calendar validation, frozen
    // +08:00 offset, deterministic output text with explicit seconds (LocalTime
    // toString() would drop zero seconds and break the frozen ISO-8601 fact shape).
    // No Clock, no timezone read from the file.
    private fun parseDateTime(
        date: String,
        time: String,
    ): String? {
        if (!DATE_SHAPE.matches(date) || !TIME_SHAPE.matches(time)) return null
        return try {
            val parsedDate = LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE)
            val parsedTime = LocalTime.parse(time)
            val timeText = parsedTime.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            "${parsedDate}T$timeText${CmbSourceTokens.UTC_OFFSET}"
        } catch (failure: DateTimeException) {
            null
        }
    }

    // Amount: frozen shape \d+\.\d{2} (non-negative, exactly two decimals). Exact
    // decimal arithmetic only, never binary floating point.
    private fun parseNonNegativeTwoDecimal(raw: String): Long? {
        if (!AMOUNT_SHAPE.matches(raw)) return null
        return try {
            BigDecimal(raw).movePointRight(CmbSourceTokens.AMOUNT_PRECISION).longValueExact()
        } catch (failure: ArithmeticException) {
            null
        } catch (failure: NumberFormatException) {
            null
        }
    }

    // Frozen tab quirk (spec section 2.1): columns 0/1/6 carry exactly one leading tab.
    private fun stripSingleLeadingTab(value: String): String? = if (value.startsWith("\t")) value.drop(1) else null

    private fun stripTrailingCarriageReturn(line: String): String = if (line.endsWith("\r")) line.dropLast(1) else line

    private fun rejected(diagnostic: CmbDiagnostic): CmbBatchResult = CmbBatchResult(CmbBatchOutcome.REJECTED, emptyList(), diagnostic)

    private val DATE_SHAPE = Regex("\\d{8}")
    private val TIME_SHAPE = Regex("\\d{2}:\\d{2}:\\d{2}")
    private val AMOUNT_SHAPE = Regex("\\d+\\.\\d{2}")
    private val BOM_CHAR = '\uFEFF'
}
