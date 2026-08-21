package com.unifiedledger.application.import.wechat

import com.unifiedledger.application.IMPORT_FUNDING_RULE_LEGACY_SETTLED
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportFundingState
import com.unifiedledger.application.ImportRecordKind
import com.unifiedledger.application.ImportSourceFacts
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.xssf.usermodel.XSSFCell
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.io.IOException
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalTime
import java.time.DateTimeException
import java.util.zip.ZipInputStream

/**
 * P4-03 WeChat bill XLSX parser (frozen spec sections 2-5).
 *
 * Deterministic pure function over an in-memory byte stream: no file/network I/O, no
 * Clock, no randomness, no path dependencies. Input = xlsx bytes + opaque synthetic
 * input ref; output = per-row Accepted(facts)/Rejected(diagnostic) results plus the
 * batch outcome. Money is derived only from the exact decimal text of the amount cell
 * (never through binary floating point). Container/input/structure diagnostics reuse
 * the D-097:1459 codes; SPINE_WEIXIN_* are the three provider-specific codes.
 */
object WechatBillParser {

    fun parse(inputRef: String, bytes: ByteArray): WechatBatchResult {
        if (bytes.size > WechatSourceTokens.MAX_INPUT_BYTES) {
            return rejected(WechatDiagnostics.unsafeOrOverLimit(inputRef))
        }
        containerViolation(inputRef, bytes)?.let { return rejected(it) }
        return try {
            XSSFWorkbook(ByteArrayInputStream(bytes)).use { workbook ->
                parseWorkbook(inputRef, workbook)
            }
        } catch (failure: Exception) {
            classifiedOpenFailure(inputRef, failure)
        }
    }

    // Container-level checks run before POI opens the package. ZipSecureFile defaults
    // remain untouched (no inflate-ratio or entry-size relaxation anywhere).
    private fun containerViolation(inputRef: String, bytes: ByteArray): WechatDiagnostic? {
        if (bytes.isEmpty()) return WechatDiagnostics.decodeFailed(inputRef)
        if (isOle2Container(bytes)) return WechatDiagnostics.unsupportedInput(inputRef)
        if (!looksLikeZipContainer(bytes)) return WechatDiagnostics.decodeFailed(inputRef)
        var hasContentTypes = false
        var hasWorkbookXml = false
        var hasVbaProject = false
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when (entry.name) {
                        "[Content_Types].xml" -> hasContentTypes = true
                        "xl/workbook.xml" -> hasWorkbookXml = true
                        "xl/vbaProject.bin" -> hasVbaProject = true
                    }
                    zip.closeEntry()
                }
            }
        } catch (failure: IOException) {
            return WechatDiagnostics.decodeFailed(inputRef)
        }
        if (hasVbaProject) return WechatDiagnostics.unsafeOrOverLimitContainer(inputRef)
        if (!hasContentTypes || !hasWorkbookXml) return WechatDiagnostics.unsupportedInput(inputRef)
        return null
    }

    private fun parseWorkbook(inputRef: String, workbook: XSSFWorkbook): WechatBatchResult {
        if (workbook.numberOfSheets == 0) {
            return rejected(WechatDiagnostics.structureMismatchHeader(inputRef))
        }
        val sheet = workbook.getSheetAt(0)
        val headerRow = sheet.getRow(WechatSourceTokens.HEADER_ROW_INDEX)
            ?: return rejected(WechatDiagnostics.structureMismatchHeader(inputRef))
        if (headerRow.lastCellNum.toInt() != WechatSourceTokens.HEADER_TOKENS.size) {
            return rejected(WechatDiagnostics.structureMismatchHeader(inputRef))
        }
        WechatSourceTokens.HEADER_TOKENS.forEachIndexed { index, token ->
            val cell = headerRow.getCell(index) ?: return rejected(WechatDiagnostics.structureMismatchHeader(inputRef))
            if (textOf(cell) != token) return rejected(WechatDiagnostics.structureMismatchHeader(inputRef))
        }
        if (sheet.lastRowNum >= WechatSourceTokens.FIRST_DATA_ROW_INDEX &&
            sheet.lastRowNum - WechatSourceTokens.FIRST_DATA_ROW_INDEX + 1 > WechatSourceTokens.MAX_DATA_ROWS
        ) {
            return rejected(WechatDiagnostics.unsafeOrOverLimit(inputRef))
        }
        val rows = mutableListOf<WechatRowResult>()
        if (sheet.lastRowNum >= WechatSourceTokens.FIRST_DATA_ROW_INDEX) {
            for (rowIndex in WechatSourceTokens.FIRST_DATA_ROW_INDEX..sheet.lastRowNum) {
                val ordinal = rowIndex - WechatSourceTokens.FIRST_DATA_ROW_INDEX
                val row = sheet.getRow(rowIndex) ?: continue // fully empty row: no record, no renumbering
                val width = row.lastCellNum.toInt()
                if (width <= 0) continue // all-empty row: no record, no renumbering
                if (width > WechatSourceTokens.HEADER_TOKENS.size) {
                    rows += WechatRowResult.Rejected(
                        ordinal, listOf(WechatDiagnostics.structureMismatchRecord(inputRef, ordinal)),
                    )
                    continue
                }
                if ((0..7).any { row.getCell(it) == null }) {
                    rows += WechatRowResult.Rejected(
                        ordinal, listOf(WechatDiagnostics.structureMismatchRecord(inputRef, ordinal)),
                    )
                    continue
                }
                rows += parseDataRow(inputRef, ordinal, row)
            }
        }
        val diagnostics = rows.flatMap { it.diagnostics }
        val outcome = if (diagnostics.isEmpty()) WechatBatchOutcome.COMPLETE else WechatBatchOutcome.PARTIAL
        return WechatBatchResult(outcome, rows, null)
    }

    // Frozen judgment order (spec section 2.1): refund first, then self-transfer,
    // missing-leg, rejected, ordinary, unknown.
    private fun parseDataRow(inputRef: String, ordinal: Int, row: Row): WechatRowResult {
        val typeToken = textOf(row.getCell(1))
        val statusTokenRaw = textOf(row.getCell(7))
        // Judgment order 1: refund (unchanged)
        if (typeToken.contains(WechatSourceTokens.REFUND_MARKER) ||
            statusTokenRaw.contains(WechatSourceTokens.REFUND_MARKER)
        ) {
            return WechatRowResult.Rejected(ordinal, listOf(WechatDiagnostics.refundUnsupported(inputRef, ordinal)))
        }
        // Determine recordKind via type routing (judgment orders 2-6)
        val recordKind = when {
            typeToken in WechatSourceTokens.TRANSFER_SELF_TX_TYPES ->
                ImportRecordKind.TRANSFER_FLOW_SOURCE
            typeToken in WechatSourceTokens.TRANSFER_MISSING_LEG_TX_TYPES ->
                ImportRecordKind.TRANSFER_FLOW_SOURCE_MISSING_LEG
            typeToken in WechatSourceTokens.REJECTED_TX_TYPES ->
                return WechatRowResult.Rejected(ordinal, listOf(WechatDiagnostics.unsupportedTxType(inputRef, ordinal)))
            typeToken in WechatSourceTokens.ACCEPTED_TX_TYPES ->
                ImportRecordKind.ORDINARY_FLOW_SOURCE
            else ->
                return WechatRowResult.Rejected(ordinal, listOf(WechatDiagnostics.unknownToken(inputRef, ordinal)))
        }
        val occurredAt = parseTime(row.getCell(0))
            ?: return WechatRowResult.Rejected(ordinal, listOf(WechatDiagnostics.fieldTimeInvalid(inputRef, ordinal)))
        val amount = parseAmount(row.getCell(5))
            ?: return WechatRowResult.Rejected(ordinal, listOf(WechatDiagnostics.fieldAmountInvalid(inputRef, ordinal)))
        val directionRaw = textOf(row.getCell(4))
        val directionMapped = WechatSourceTokens.DIRECTION_TOKEN_MAP[directionRaw]
        val statusMapped = statusTokenRaw in WechatSourceTokens.SETTLED_STATUS_TOKENS
        val diagnostics = mutableListOf<WechatDiagnostic>()
        if (directionMapped == null) {
            diagnostics += WechatDiagnostics.requiredFactUnresolved(
                inputRef, ordinal, WechatSourceTokens.FIELD_ROLE_DIRECTION,
            )
        }
        // Self-transfer direction matrix check (spec section 2.2)
        if (recordKind == ImportRecordKind.TRANSFER_FLOW_SOURCE && directionMapped != null) {
            val expectedDirection = when (typeToken) {
                "零钱提现" -> "out"
                "零钱充值" -> "in"
                else -> null
            }
            if (expectedDirection != null && directionMapped != expectedDirection) {
                return WechatRowResult.Rejected(
                    ordinal, listOf(WechatDiagnostics.conflictingSourceFacts(inputRef, ordinal)),
                )
            }
        }
        if (!statusMapped) {
            diagnostics += WechatDiagnostics.requiredFactUnresolved(
                inputRef, ordinal, WechatSourceTokens.FIELD_ROLE_STATUS,
            )
        }
        val completeness = if (diagnostics.isEmpty()) ImportCompleteness.VALID_COMPLETE else ImportCompleteness.VALID_INCOMPLETE
        // D-105 section 4: no approved funding-state provider contract exists for WeChat
        // tokens yet, so the parser relays only the frozen legacy-settled funding facts.
        val facts = ImportSourceFacts(
            amountMinor = amount.minor,
            currencyCode = WechatSourceTokens.CURRENCY_CNY,
            currencyPrecision = amount.precision,
            occurredAt = occurredAt,
            directionToken = directionMapped ?: directionRaw,
            statusToken = if (statusMapped) WechatSourceTokens.STATUS_SETTLED else statusTokenRaw.ifEmpty { null },
            fundingState = ImportFundingState.SETTLED,
            fundingRuleId = IMPORT_FUNDING_RULE_LEGACY_SETTLED,
            fundingRuleVersion = 1,
        )
        return WechatRowResult.Accepted(ordinal, recordKind, facts, completeness, diagnostics)
    }

    private data class ParsedAmount(val minor: Long, val precision: Int)

    // Amount: NUMERIC cell, cached raw decimal text only. Precision = fractional digit
    // count of the exact cell text; negative values are out of the frozen domain.
    private fun parseAmount(cell: Cell?): ParsedAmount? {
        if (cell == null || cell.cellType != CellType.NUMERIC) return null
        val raw = rawTextOf(cell)
        if (raw.isEmpty() || !AMOUNT_DECIMAL.matches(raw)) return null
        val precision = if (raw.contains('.')) raw.length - raw.indexOf('.') - 1 else 0
        val minor = try {
            BigDecimal(raw).movePointRight(precision).longValueExact()
        } catch (failure: ArithmeticException) {
            return null
        } catch (failure: NumberFormatException) {
            return null
        }
        return ParsedAmount(minor, precision)
    }

    // Time: NUMERIC cell (excel datetime serial), exact BigDecimal conversion at second
    // resolution, frozen +08:00 offset. No Clock, no timezone read from the file.
    private fun parseTime(cell: Cell?): String? {
        if (cell == null || cell.cellType != CellType.NUMERIC) return null
        return excelSerialToIso(rawTextOf(cell))
    }

    private fun excelSerialToIso(raw: String): String? {
        val serial = try {
            BigDecimal(raw.trim())
        } catch (failure: NumberFormatException) {
            return null
        }
        if (serial.signum() < 0 || serial >= MAX_EXCEL_SERIAL) return null
        val totalSeconds = try {
            serial.multiply(SECONDS_PER_DAY).setScale(0, RoundingMode.HALF_UP).toBigInteger()
        } catch (failure: ArithmeticException) {
            return null
        }
        val days = totalSeconds.divide(SECONDS_PER_DAY_BIG)
        val secondsOfDay = totalSeconds.mod(SECONDS_PER_DAY_BIG).toLong()
        val date = try {
            EXCEL_EPOCH.plusDays(days.toLong())
        } catch (failure: DateTimeException) {
            return null
        } catch (failure: ArithmeticException) {
            return null
        }
        val time = LocalTime.ofSecondOfDay(secondsOfDay)
        val hh = time.hour.toString().padStart(2, '0')
        val mm = time.minute.toString().padStart(2, '0')
        val ss = time.second.toString().padStart(2, '0')
        return "${date}T$hh:$mm:$ss${WechatSourceTokens.UTC_OFFSET}"
    }

    // STRING cells read via the shared string table; every other type via the raw
    // cached value text. Columns 2/3/6/8/9/10 values are never materialized into
    // output/diagnostics; only their cell presence feeds the row-width contract.
    private fun textOf(cell: Cell?): String = when {
        cell == null -> ""
        cell.cellType == CellType.STRING -> cell.stringCellValue
        else -> rawTextOf(cell)
    }

    private fun rawTextOf(cell: Cell): String = (cell as? XSSFCell)?.getRawValue() ?: ""

    // POI open failures: ZipSecureFile protection triggers (default parameters, never
    // relaxed) map to INPUT_UNSAFE_OR_OVER_LIMIT; other container failures to
    // INPUT_DECODE_FAILED. The cause chain is walked so wrapper exceptions do not
    // change the classification.
    private fun classifiedOpenFailure(inputRef: String, failure: Exception): WechatBatchResult {
        var current: Throwable? = failure
        while (current != null) {
            if (current is IOException) {
                val message = current.message ?: ""
                if (message.contains("zip bomb", ignoreCase = true)) {
                    return rejected(WechatDiagnostics.unsafeOrOverLimitContainer(inputRef))
                }
            }
            current = current.cause
        }
        return rejected(WechatDiagnostics.decodeFailed(inputRef))
    }

    private fun rejected(diagnostic: WechatDiagnostic): WechatBatchResult =
        WechatBatchResult(WechatBatchOutcome.REJECTED, emptyList(), diagnostic)

    private fun isOle2Container(bytes: ByteArray): Boolean =
        bytes.size >= 8 &&
            bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte() && bytes[2] == 0x11.toByte() &&
            bytes[3] == 0xE0.toByte() && bytes[4] == 0xA1.toByte() && bytes[5] == 0xB1.toByte() &&
            bytes[6] == 0x1A.toByte() && bytes[7] == 0xE1.toByte()

    private fun looksLikeZipContainer(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
            ((bytes[2] == 3.toByte() && bytes[3] == 4.toByte()) ||
                (bytes[2] == 5.toByte() && bytes[3] == 6.toByte()) ||
                (bytes[2] == 7.toByte() && bytes[3] == 8.toByte()))

    private val AMOUNT_DECIMAL = Regex("\\d+(\\.\\d+)?")

    private val EXCEL_EPOCH: LocalDate = LocalDate.of(1899, 12, 30)
    private val SECONDS_PER_DAY = BigDecimal("86400")
    private val SECONDS_PER_DAY_BIG = BigInteger.valueOf(86400)
    private val MAX_EXCEL_SERIAL = BigDecimal("2958466") // 9999-12-31 in the Excel epoch
}
