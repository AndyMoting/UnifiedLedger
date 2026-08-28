package com.unifiedledger.application.import.ccb

import com.unifiedledger.application.IMPORT_FUNDING_RULE_LEGACY_SETTLED
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportFundingState
import com.unifiedledger.application.ImportRecordKind
import com.unifiedledger.application.ImportSourceFacts
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Row
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DateTimeException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * BP-01 CCB online-banking XLS parser (frozen spec sections 2.2, 2.3, 2.4, 3.2, 3.3).
 *
 * Deterministic pure function over an in-memory byte stream: no file/network I/O, no
 * Clock, no randomness, no path dependencies. Input = OLE2/BIFF `.xls` bytes (single
 * sheet `Sheet0`) + opaque synthetic input ref; output = per-row
 * Accepted(facts)/Rejected(diagnostic) results plus the batch outcome. All evidence
 * cells are text strings; numeric cells are decoded to their exact decimal text (never
 * through binary floating point arithmetic) with the frozen precision-2 normalization
 * for amount/balance fields. The title area (0-based rows 0..2) is never read; the
 * header is frozen at 0-based row 3 with a byte-exact 9-field match.
 *
 * Balance mirror (spec section 2.3): each row's declared balance participates in an
 * ascending-chain continuity assertion over the raw (pre-routing) rows; rejected rows
 * still participate, record_error rows do not (the chain re-anchors), and a mismatch
 * is a non-blocking SPINE_BANK_BALANCE_CONTINUITY note. Balance values never enter the
 * five source facts.
 */
object CcbBillParser {
    fun parse(
        inputRef: String,
        bytes: ByteArray,
    ): CcbBatchResult {
        bytePrecheck(inputRef, bytes)?.let { return rejected(it) }
        return try {
            HSSFWorkbook(ByteArrayInputStream(bytes)).use { workbook ->
                parseWorkbook(inputRef, workbook)
            }
        } catch (failure: Exception) {
            rejected(CcbDiagnostics.decodeFailed(inputRef))
        }
    }

    // Frozen byte pre-check order (spec section 2.2): empty, over-limit, OLE2 magic.
    private fun bytePrecheck(
        inputRef: String,
        bytes: ByteArray,
    ): CcbDiagnostic? {
        if (bytes.isEmpty()) return CcbDiagnostics.decodeFailed(inputRef)
        if (bytes.size > CcbSourceTokens.MAX_INPUT_BYTES) return CcbDiagnostics.unsafeOrOverLimit(inputRef)
        if (!isOle2Container(bytes)) return CcbDiagnostics.decodeFailed(inputRef)
        return null
    }

    private fun parseWorkbook(
        inputRef: String,
        workbook: HSSFWorkbook,
    ): CcbBatchResult {
        if (workbook.numberOfSheets != 1 || workbook.getSheetName(0) != CcbSourceTokens.SHEET_NAME) {
            return rejected(CcbDiagnostics.structureMismatchHeader(inputRef))
        }
        val sheet = workbook.getSheetAt(0)
        // Fewer than five physical rows means no header at 0-based row 3 (spec 2.2).
        if (sheet.lastRowNum < CcbSourceTokens.FIRST_DATA_ROW_INDEX) {
            return rejected(CcbDiagnostics.structureMismatchHeader(inputRef))
        }
        val headerRow =
            sheet.getRow(CcbSourceTokens.HEADER_ROW_INDEX)
                ?: return rejected(CcbDiagnostics.structureMismatchHeader(inputRef))
        if (headerRow.lastCellNum.toInt() != CcbSourceTokens.FIELD_COUNT) {
            return rejected(CcbDiagnostics.structureMismatchHeader(inputRef))
        }
        CcbSourceTokens.HEADER_TOKENS.forEachIndexed { index, token ->
            val cell = headerRow.getCell(index) ?: return rejected(CcbDiagnostics.structureMismatchHeader(inputRef))
            if (cellText(cell) != token) return rejected(CcbDiagnostics.structureMismatchHeader(inputRef))
        }
        if (sheet.lastRowNum - CcbSourceTokens.FIRST_DATA_ROW_INDEX + 1 > CcbSourceTokens.MAX_DATA_ROWS) {
            return rejected(CcbDiagnostics.unsafeOrOverLimit(inputRef))
        }
        val rows = mutableListOf<CcbRowResult>()
        var previous: CcbBalanceObservation? = null
        for (rowIndex in CcbSourceTokens.FIRST_DATA_ROW_INDEX..sheet.lastRowNum) {
            val ordinal = rowIndex - CcbSourceTokens.FIRST_DATA_ROW_INDEX
            val row = sheet.getRow(rowIndex) ?: continue // fully empty row: no record, no renumbering
            val width = row.lastCellNum.toInt()
            if (width <= 0) continue // all-empty row: no record, no renumbering
            if (width > CcbSourceTokens.FIELD_COUNT) {
                // Record-level structure violation: the row cannot participate in the
                // continuity chain; the next valid row re-anchors (spec section 2.3).
                previous = null
                rows += CcbRowResult.Rejected(ordinal, listOf(CcbDiagnostics.structureMismatchRecord(inputRef, ordinal)))
                continue
            }
            val observation = observe(row)
            val parsed = parseDataRow(inputRef, ordinal, row)
            val result =
                if (observation != null && previous != null && parsed is CcbRowResult.Accepted) {
                    val expected = previous.balanceMinor + observation.signedAmountMinor
                    if (observation.balanceMinor != expected) {
                        parsed.copy(
                            diagnostics = parsed.diagnostics + CcbDiagnostics.balanceContinuity(inputRef, ordinal),
                        )
                    } else {
                        parsed
                    }
                } else {
                    parsed
                }
            // Chain base: the immediately previous physical data row. A non-participating
            // row (record_error / record-level structure violation) sets previous = null,
            // so the next valid row re-anchors without a check (spec section 2.3 item 3).
            previous = observation
            rows += result
        }
        val diagnostics = rows.flatMap { it.diagnostics }
        val outcome = if (diagnostics.isEmpty()) CcbBatchOutcome.COMPLETE else CcbBatchOutcome.PARTIAL
        return CcbBatchResult(outcome, rows, null)
    }

    /**
     * Raw-row balance observation used by the ascending-chain continuity assertion
     * (spec section 2.3). Non-null only when the row can participate: all structural
     * invariants hold, the date parses, and amount + balance are exactly decodable.
     */
    private data class CcbBalanceObservation(
        val balanceMinor: Long,
        val signedAmountMinor: Long,
    )

    private fun observe(row: Row): CcbBalanceObservation? {
        if (!INTEGER_SHAPE.matches(cellText(row.getCell(0)))) return null
        if (cellText(row.getCell(2)).isEmpty()) return null
        if (cellText(row.getCell(3)) !in CcbSourceTokens.CURRENCY_KIND_VALUES) return null
        val dateCell = row.getCell(4) ?: return null
        if (parseDateText(cellText(dateCell)) == null) return null
        val amountCell = row.getCell(5) ?: return null
        val amountMinor = parseSignedAmount(amountCell) ?: return null
        val balanceCell = row.getCell(6) ?: return null
        val balanceMinor = parseBalanceMinor(balanceCell) ?: return null
        return CcbBalanceObservation(balanceMinor, amountMinor)
    }

    // Frozen judgment order (spec section 3.3): refund first (summary or remark),
    // then (this batch has no independent reject set), unknown, accept routing.
    // Direction always comes from the signed amount (spec section 2.3), never from
    // the summary token.
    private fun parseDataRow(
        inputRef: String,
        ordinal: Int,
        row: Row,
    ): CcbRowResult {
        // Row structure invariants (spec section 2.2): 序号 integer text; 币别/钞汇/
        // 日期/金额/余额 required; 摘要/附言/对方账号 may be empty.
        if (!INTEGER_SHAPE.matches(cellText(row.getCell(0)))) return rejectedRecord(inputRef, ordinal)
        if (cellText(row.getCell(2)).isEmpty()) return rejectedRecord(inputRef, ordinal)
        if (cellText(row.getCell(3)) !in CcbSourceTokens.CURRENCY_KIND_VALUES) return rejectedRecord(inputRef, ordinal)
        val dateText = cellText(row.getCell(4))
        if (dateText.isEmpty()) return rejectedRecord(inputRef, ordinal)
        val amountCell = row.getCell(5)
        if (cellText(amountCell).isEmpty()) {
            return CcbRowResult.Rejected(ordinal, listOf(CcbDiagnostics.fieldAmountInvalid(inputRef, ordinal)))
        }

        val summary = cellText(row.getCell(1))
        val remark = cellText(row.getCell(7))
        // Judgment order 1: refund marker in the summary or the remark.
        if (summary.contains(CcbSourceTokens.REFUND_MARKER) ||
            remark.contains(CcbSourceTokens.REFUND_MARKER)
        ) {
            return CcbRowResult.Rejected(ordinal, listOf(CcbDiagnostics.refundUnsupported(inputRef, ordinal)))
        }
        // Judgment order 3: unknown summary token fail-closed.
        val recordKind =
            when {
                summary in CcbSourceTokens.TRANSFER_SUMMARY_TYPES -> ImportRecordKind.TRANSFER_FLOW_SOURCE
                summary in CcbSourceTokens.ORDINARY_SUMMARY_TYPES -> ImportRecordKind.ORDINARY_FLOW_SOURCE
                else -> return CcbRowResult.Rejected(ordinal, listOf(CcbDiagnostics.unknownToken(inputRef, ordinal)))
            }

        val occurredAt =
            parseDateText(dateText)
                ?: return CcbRowResult.Rejected(ordinal, listOf(CcbDiagnostics.fieldTimeInvalid(inputRef, ordinal)))
        val signedMinor =
            parseSignedAmount(amountCell)
                ?: return CcbRowResult.Rejected(ordinal, listOf(CcbDiagnostics.fieldAmountInvalid(inputRef, ordinal)))

        // Direction from the mechanical sign (spec section 2.3, rule
        // amount_sign_direction_v1): the sign decides the direction fact only. The
        // five-fact amount is the positive magnitude in minor units (D-097 fact
        // shape; the spine formalization binding requires a positive amount, and the
        // CMB/Alipay/WeChat precedent parsers all emit magnitudes). Zero amount:
        // direction unresolved, valid_incomplete, not confirmable (fail-closed).
        val directionToken =
            when {
                signedMinor < 0L -> "out"
                signedMinor > 0L -> "in"
                else -> null
            }
        val amountMinor = if (signedMinor < 0L) -signedMinor else signedMinor

        // Balance column anomaly is a non-blocking note (spec section 2.3 item 4).
        val diagnostics = mutableListOf<CcbDiagnostic>()
        if (parseBalanceMinor(row.getCell(6)) == null) {
            diagnostics += CcbDiagnostics.balanceMissing(inputRef, ordinal)
        }
        if (directionToken == null) {
            diagnostics += CcbDiagnostics.requiredFactUnresolved(inputRef, ordinal, CcbSourceTokens.FIELD_ROLE_DIRECTION)
        }

        val facts =
            ImportSourceFacts(
                amountMinor = amountMinor,
                currencyCode = CcbSourceTokens.CURRENCY_CNY,
                currencyPrecision = CcbSourceTokens.AMOUNT_PRECISION,
                occurredAt = occurredAt,
                directionToken = directionToken ?: signToken(amountCell),
                statusToken = CcbSourceTokens.STATUS_SETTLED,
                fundingState = ImportFundingState.SETTLED,
                fundingRuleId = IMPORT_FUNDING_RULE_LEGACY_SETTLED,
                fundingRuleVersion = 1,
            )
        val completeness = if (diagnostics.isEmpty()) ImportCompleteness.VALID_COMPLETE else ImportCompleteness.VALID_INCOMPLETE
        return CcbRowResult.Accepted(ordinal, recordKind, facts, completeness, diagnostics)
    }

    private fun rejectedRecord(
        inputRef: String,
        ordinal: Int,
    ): CcbRowResult = CcbRowResult.Rejected(ordinal, listOf(CcbDiagnostics.structureMismatchRecord(inputRef, ordinal)))

    // Exact cell text: STRING cells verbatim; NUMERIC cells via the exact decimal text
    // of the stored value (BigDecimal.valueOf, never binary-float arithmetic).
    private fun cellText(cell: Cell?): String =
        when {
            cell == null -> ""
            cell.cellType == CellType.STRING -> cell.stringCellValue
            cell.cellType == CellType.NUMERIC -> BigDecimal.valueOf(cell.numericCellValue).toPlainString()
            else -> ""
        }

    // Amount: signed exact two-decimal text for STRING cells; for NUMERIC cells the
    // stored value must be exactly representable at the frozen precision 2 (otherwise
    // fail-closed). Exact decimal arithmetic only, never binary floating point.
    private fun parseSignedAmount(cell: Cell): Long? =
        when (cell.cellType) {
            CellType.STRING -> {
                val text = cell.stringCellValue
                if (!SIGNED_AMOUNT_SHAPE.matches(text)) {
                    null
                } else {
                    try {
                        BigDecimal(text).movePointRight(CcbSourceTokens.AMOUNT_PRECISION).longValueExact()
                    } catch (failure: ArithmeticException) {
                        null
                    } catch (failure: NumberFormatException) {
                        null
                    }
                }
            }
            CellType.NUMERIC ->
                try {
                    BigDecimal
                        .valueOf(cell.numericCellValue)
                        .setScale(CcbSourceTokens.AMOUNT_PRECISION, RoundingMode.UNNECESSARY)
                        .movePointRight(CcbSourceTokens.AMOUNT_PRECISION)
                        .longValueExact()
                } catch (failure: ArithmeticException) {
                    null
                }
            else -> null
        }

    // Balance: non-negative exact two-decimal (spec section 2.2); a negative or
    // non-two-decimal value is a balance-column anomaly (non-blocking note).
    private fun parseBalanceMinor(cell: Cell?): Long? {
        if (cell == null) return null
        return when (cell.cellType) {
            CellType.STRING -> {
                val text = cell.stringCellValue
                if (!BALANCE_SHAPE.matches(text)) {
                    null
                } else {
                    try {
                        BigDecimal(text).movePointRight(CcbSourceTokens.AMOUNT_PRECISION).longValueExact()
                    } catch (failure: ArithmeticException) {
                        null
                    } catch (failure: NumberFormatException) {
                        null
                    }
                }
            }
            CellType.NUMERIC ->
                try {
                    val decimal = BigDecimal.valueOf(cell.numericCellValue)
                    if (decimal.signum() < 0) {
                        null
                    } else {
                        decimal
                            .setScale(CcbSourceTokens.AMOUNT_PRECISION, RoundingMode.UNNECESSARY)
                            .movePointRight(CcbSourceTokens.AMOUNT_PRECISION)
                            .longValueExact()
                    }
                } catch (failure: ArithmeticException) {
                    null
                }
            else -> null
        }
    }

    // Date: frozen YYYYMMDD text (numeric cells decode to plain integer text), strict
    // calendar validation, frozen midnight fill T00:00:00+08:00 (deterministic
    // mechanical fill, not Clock; spec section 8 item 6).
    private fun parseDateText(raw: String): String? {
        if (!DATE_SHAPE.matches(raw)) return null
        return try {
            val parsed = LocalDate.parse(raw, DateTimeFormatter.BASIC_ISO_DATE)
            "${parsed}T00:00:00${CcbSourceTokens.UTC_OFFSET}"
        } catch (failure: DateTimeException) {
            null
        }
    }

    // Raw sign token preserved for a zero-amount row whose direction is unresolved
    // (D-097 raw-token preservation; the sign cannot express direction for zero).
    private fun signToken(amountCell: Cell): String = if (cellText(amountCell).startsWith("-")) "-" else "+"

    private fun rejected(diagnostic: CcbDiagnostic): CcbBatchResult = CcbBatchResult(CcbBatchOutcome.REJECTED, emptyList(), diagnostic)

    private fun isOle2Container(bytes: ByteArray): Boolean =
        bytes.size >= 8 &&
            bytes[0] == 0xD0.toByte() &&
            bytes[1] == 0xCF.toByte() &&
            bytes[2] == 0x11.toByte() &&
            bytes[3] == 0xE0.toByte() &&
            bytes[4] == 0xA1.toByte() &&
            bytes[5] == 0xB1.toByte() &&
            bytes[6] == 0x1A.toByte() &&
            bytes[7] == 0xE1.toByte()

    private val INTEGER_SHAPE = Regex("\\d+")
    private val DATE_SHAPE = Regex("\\d{8}")
    private val SIGNED_AMOUNT_SHAPE = Regex("-?\\d+\\.\\d{2}")
    private val BALANCE_SHAPE = Regex("\\d+\\.\\d{2}")
}
