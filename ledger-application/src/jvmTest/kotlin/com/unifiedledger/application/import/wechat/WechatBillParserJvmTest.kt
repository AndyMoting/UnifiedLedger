package com.unifiedledger.application.import.wechat

import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportSourceFacts
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P4-03 parser oracle (frozen spec section 1.3, P-01..P-21 and T-16..T-20).
 *
 * All workbooks are synthetic and provider-neutral, generated in-test with POI. Amount
 * cells whose frozen raw text needs trailing zeros ("128.50", "0.0", "0.00") use
 * numeric marker values that are rewritten in the sheet XML after generation, so the
 * parser always sees the exact frozen cell text.
 */
class WechatBillParserJvmTest {

    private val inputRef = "batch-p403-a"

    private sealed interface CellSpec
    private data class TextSpec(val value: String) : CellSpec
    private data class NumSpec(val value: Double) : CellSpec

    private fun num(value: Double) = NumSpec(value)
    private fun text(value: String) = TextSpec(value)

    private fun serialOf(date: LocalDate, hour: Int, minute: Int): Double {
        val days = ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), date).toDouble()
        return days + (hour * 3600 + minute * 60) / 86400.0
    }

    private inner class WorkbookBuilder {
        private val workbook = XSSFWorkbook()
        val sheet: XSSFSheet = workbook.createSheet()

        fun metadataRows() {
            for (r in 0..16) {
                val row = sheet.createRow(r)
                row.createCell(0).setCellValue("SYN-META-PII-EXPORT-$r")
                row.createCell(1).setCellValue("SYN-META-PII-NICK-$r")
            }
        }

        fun header() {
            val row = sheet.createRow(WechatSourceTokens.HEADER_ROW_INDEX)
            WechatSourceTokens.HEADER_TOKENS.forEachIndexed { index, token ->
                row.createCell(index).setCellValue(token)
            }
        }

        fun headerVariant(cells: List<Pair<Int, String>>) {
            val row = sheet.createRow(WechatSourceTokens.HEADER_ROW_INDEX)
            cells.forEach { (index, value) -> row.createCell(index).setCellValue(value) }
        }

        fun dataRow(rowIndex: Int, cells: List<Pair<Int, CellSpec>>) {
            val row = sheet.createRow(rowIndex)
            cells.forEach { (index, spec) ->
                val cell = row.createCell(index)
                when (spec) {
                    is TextSpec -> cell.setCellValue(spec.value)
                    is NumSpec -> cell.setCellValue(spec.value)
                }
            }
        }

        fun bytes(replacements: Map<String, String> = emptyMap()): ByteArray {
            val raw = ByteArrayOutputStream()
            workbook.write(raw)
            workbook.close()
            if (replacements.isEmpty()) return raw.toByteArray()
            return rewriteSheetXml(raw.toByteArray(), replacements)
        }
    }

    private fun rewriteSheetXml(bytes: ByteArray, replacements: Map<String, String>): ByteArray =
        rewriteZip(bytes) { entryName, content ->
            if (entryName == "xl/worksheets/sheet1.xml") {
                var text = content.toString(Charsets.UTF_8)
                replacements.forEach { (old, new) -> text = text.replace(old, new) }
                text.toByteArray(Charsets.UTF_8)
            } else {
                content
            }
        }

    private fun rewriteZip(bytes: ByteArray, transform: (String, ByteArray) -> ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val transformed = transform(entry.name, zis.readBytes())
                    zos.putNextEntry(ZipEntry(entry.name))
                    zos.write(transformed)
                    zos.closeEntry()
                    zis.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }

    private fun addZipEntry(bytes: ByteArray, entryName: String, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    zos.putNextEntry(ZipEntry(entry.name))
                    zos.write(zis.readBytes())
                    zos.closeEntry()
                    zis.closeEntry()
                }
            }
            zos.putNextEntry(ZipEntry(entryName))
            zos.write(content)
            zos.closeEntry()
        }
        return out.toByteArray()
    }

    private fun rowCells(
        time: CellSpec,
        type: String,
        direction: String,
        amount: CellSpec,
        status: String,
        counterparty: String = "SYN-SECRET-COUNTERPARTY",
        product: String = "SYN-SECRET-PRODUCT",
        method: String = "SYN-SECRET-METHOD",
        txNo: String? = "SYN-SECRET-TXNO",
        merchNo: String? = "SYN-SECRET-MERCHNO",
        note: String? = "SYN-SECRET-NOTE",
    ): List<Pair<Int, CellSpec>> = listOfNotNull(
        0 to time, 1 to TextSpec(type), 2 to TextSpec(counterparty), 3 to TextSpec(product),
        4 to TextSpec(direction), 5 to amount, 6 to TextSpec(method), 7 to TextSpec(status),
        txNo?.let { 8 to TextSpec(it) }, merchNo?.let { 9 to TextSpec(it) }, note?.let { 10 to TextSpec(it) },
    )

    private fun workbookA(): ByteArray {
        val builder = WorkbookBuilder()
        builder.metadataRows()
        builder.header()
        // W1: accepted expense, raw "128.50", tx order id empty, valid time.
        builder.dataRow(18, rowCells(num(serialOf(LocalDate.of(2026, 8, 1), 12, 30)), "商户消费", "支出", num(111.11), "支付成功", txNo = null))
        // W2: accepted expense, raw "12.5".
        builder.dataRow(19, rowCells(num(serialOf(LocalDate.of(2026, 8, 5), 9, 0)), "扫二维码付款", "支出", num(12.5), "支付成功"))
        // W3: accepted income, raw "88", merchant order id empty.
        builder.dataRow(20, rowCells(num(serialOf(LocalDate.of(2026, 8, 6), 18, 45)), "二维码收款", "收入", num(100.3), "已存入零钱", merchNo = null))
        // W4: accepted income, raw "3.00".
        builder.dataRow(21, rowCells(num(serialOf(LocalDate.of(2026, 8, 8), 10, 0)), "赞赏码", "收入", num(222.22), "已到账"))
        // W5: accepted expense, raw "45.6", both order id columns empty.
        builder.dataRow(22, rowCells(num(serialOf(LocalDate.of(2026, 8, 9), 21, 15)), "其他", "支出", num(45.6), "支付成功", txNo = null, merchNo = null))
        // W6: neutral direction "/", raw "0.00".
        builder.dataRow(23, rowCells(num(serialOf(LocalDate.of(2026, 8, 10), 8, 0)), "商户消费", "/", num(333.33), "支付成功"))
        // W7: out-of-scope type 零钱提现.
        builder.dataRow(24, rowCells(num(serialOf(LocalDate.of(2026, 8, 10), 9, 30)), "零钱提现", "支出", num(444.44), "提现已到账"))
        // W8: refund type variant 商户消费-退款 + refund status 已退款¥128.50.
        builder.dataRow(25, rowCells(num(serialOf(LocalDate.of(2026, 8, 11), 11, 0)), "商户消费-退款", "收入", num(555.55), "已退款¥128.50"))
        // W9: accepted type but refund status 已退款(10.00).
        builder.dataRow(26, rowCells(num(serialOf(LocalDate.of(2026, 8, 11), 12, 0)), "商户消费", "支出", num(666.66), "已退款(10.00)"))
        // W10: unknown direction token 出账, raw "20.00".
        builder.dataRow(27, rowCells(num(serialOf(LocalDate.of(2026, 8, 11), 13, 0)), "商户消费", "出账", num(777.77), "支付成功"))
        // W11: amount cell is text.
        builder.dataRow(28, rowCells(num(serialOf(LocalDate.of(2026, 8, 12), 7, 30)), "商户消费", "支出", text("abc"), "支付成功"))
        // W12: unknown type token.
        builder.dataRow(29, rowCells(num(serialOf(LocalDate.of(2026, 8, 12), 8, 45)), "神秘交易类型", "支出", num(9.9), "支付成功"))
        // W13: time cell is text.
        builder.dataRow(30, rowCells(text("不是时间"), "商户消费", "支出", num(888.88), "支付成功"))
        // W14: unmapped status token 交易关闭, raw "7.00".
        builder.dataRow(31, rowCells(num(serialOf(LocalDate.of(2026, 8, 12), 9, 0)), "商户消费", "支出", num(999.99), "交易关闭"))
        return builder.bytes(
            mapOf(
                "111.11" to "128.50",
                "222.22" to "3.00",
                "333.33" to "0.00",
                "444.44" to "100.00",
                "555.55" to "128.50",
                "666.66" to "10.00",
                "777.77" to "20.00",
                "888.88" to "10.00",
                "999.99" to "7.00",
                "100.3" to "88",
            ),
        )
    }

    private fun accepted(rows: List<WechatRowResult>, ordinal: Int): WechatRowResult.Accepted {
        val row = rows.first { it.recordOrdinal == ordinal }
        return assertIs<WechatRowResult.Accepted>(row)
    }

    private fun rejected(rows: List<WechatRowResult>, ordinal: Int): WechatRowResult.Rejected {
        val row = rows.first { it.recordOrdinal == ordinal }
        return assertIs<WechatRowResult.Rejected>(row)
    }

    private fun assertDiagnostic(
        diagnostic: WechatDiagnostic,
        code: String,
        severity: String,
        scope: String,
        ordinal: Int? = null,
        fieldRole: String? = null,
        expectedInputRef: String = inputRef,
    ) {
        assertEquals(code, diagnostic.code)
        assertEquals(severity, diagnostic.severity)
        assertEquals(scope, diagnostic.scope)
        assertEquals(expectedInputRef, diagnostic.inputRef)
        assertEquals(ordinal, diagnostic.recordOrdinal)
        assertEquals(fieldRole, diagnostic.fieldRole)
    }

    @Test
    fun acceptedOrdinaryRowsW1ToW5MatchFrozenFacts() {
        val result = WechatBillParser.parse(inputRef, workbookA())
        assertEquals(WechatBatchOutcome.PARTIAL, result.outcome)

        val w1 = accepted(result.rows, 0)
        assertEquals(ImportSourceFacts(12850, "CNY", 2, "2026-08-01T12:30:00+08:00", "out", "settled"), w1.facts)
        assertEquals(ImportCompleteness.VALID_COMPLETE, w1.completeness)
        assertEquals(emptyList(), w1.diagnostics)

        val w2 = accepted(result.rows, 1)
        assertEquals(ImportSourceFacts(125, "CNY", 1, "2026-08-05T09:00:00+08:00", "out", "settled"), w2.facts)
        assertEquals(ImportCompleteness.VALID_COMPLETE, w2.completeness)

        val w3 = accepted(result.rows, 2)
        assertEquals(ImportSourceFacts(88, "CNY", 0, "2026-08-06T18:45:00+08:00", "in", "settled"), w3.facts)
        assertEquals(ImportCompleteness.VALID_COMPLETE, w3.completeness)

        val w4 = accepted(result.rows, 3)
        assertEquals(ImportSourceFacts(300, "CNY", 2, "2026-08-08T10:00:00+08:00", "in", "settled"), w4.facts)
        assertEquals(ImportCompleteness.VALID_COMPLETE, w4.completeness)

        val w5 = accepted(result.rows, 4)
        assertEquals(ImportSourceFacts(456, "CNY", 1, "2026-08-09T21:15:00+08:00", "out", "settled"), w5.facts)
        assertEquals(ImportCompleteness.VALID_COMPLETE, w5.completeness)
    }

    @Test
    fun neutralAndUnknownDirectionRowsAreValidIncompleteWithUnresolvedDirection() {
        val result = WechatBillParser.parse(inputRef, workbookA())

        val w6 = accepted(result.rows, 5)
        assertEquals(ImportSourceFacts(0, "CNY", 2, "2026-08-10T08:00:00+08:00", "/", "settled"), w6.facts)
        assertEquals(ImportCompleteness.VALID_INCOMPLETE, w6.completeness)
        assertEquals(1, w6.diagnostics.size)
        assertDiagnostic(w6.diagnostics[0], "REQUIRED_FACT_UNRESOLVED", "incomplete", "field", 5, "direction")

        val w10 = accepted(result.rows, 9)
        assertEquals(ImportSourceFacts(2000, "CNY", 2, "2026-08-11T13:00:00+08:00", "出账", "settled"), w10.facts)
        assertEquals(ImportCompleteness.VALID_INCOMPLETE, w10.completeness)
        assertEquals(1, w10.diagnostics.size)
        assertDiagnostic(w10.diagnostics[0], "REQUIRED_FACT_UNRESOLVED", "incomplete", "field", 9, "direction")
    }

    @Test
    fun unmappedStatusTokenRowIsValidIncompleteWithUnresolvedStatus() {
        val result = WechatBillParser.parse(inputRef, workbookA())

        val w14 = accepted(result.rows, 13)
        assertEquals(ImportSourceFacts(700, "CNY", 2, "2026-08-12T09:00:00+08:00", "out", "交易关闭"), w14.facts)
        assertEquals(ImportCompleteness.VALID_INCOMPLETE, w14.completeness)
        assertEquals(1, w14.diagnostics.size)
        assertDiagnostic(w14.diagnostics[0], "REQUIRED_FACT_UNRESOLVED", "incomplete", "field", 13, "status")
    }

    @Test
    fun rejectedRowsCarryFrozenDiagnostics() {
        val result = WechatBillParser.parse(inputRef, workbookA())

        val w8 = rejected(result.rows, 7)
        assertDiagnostic(w8.diagnostics.single(), "SPINE_WEIXIN_REFUND_UNSUPPORTED", "unsupported", "record", 7)

        val w9 = rejected(result.rows, 8)
        assertDiagnostic(w9.diagnostics.single(), "SPINE_WEIXIN_REFUND_UNSUPPORTED", "unsupported", "record", 8)

        val w11 = rejected(result.rows, 10)
        assertDiagnostic(w11.diagnostics.single(), "FIELD_AMOUNT_INVALID", "record_error", "field", 10, "amount")

        val w12 = rejected(result.rows, 11)
        assertDiagnostic(w12.diagnostics.single(), "SPINE_WEIXIN_UNKNOWN_TOKEN", "unsupported", "record", 11)

        val w13 = rejected(result.rows, 12)
        assertDiagnostic(w13.diagnostics.single(), "FIELD_TIME_INVALID", "record_error", "field", 12, "occurred_at")
    }

    @Test
    fun wholeBatchOutcomeIsPartialWithNineRecordsAndEightDiagnostics() {
        val result = WechatBillParser.parse(inputRef, workbookA())
        assertEquals(WechatBatchOutcome.PARTIAL, result.outcome)
        assertNull(result.diagnostic)
        assertEquals(14, result.rows.size)
        assertEquals(9, result.rows.count { it is WechatRowResult.Accepted })
        assertEquals(5, result.rows.count { it is WechatRowResult.Rejected })
        val byCode = result.rows.flatMap { it.diagnostics }.groupingBy { it.code }.eachCount()
        assertEquals(
            mapOf(
                "SPINE_WEIXIN_REFUND_UNSUPPORTED" to 2,
                "SPINE_WEIXIN_UNKNOWN_TOKEN" to 1,
                "FIELD_AMOUNT_INVALID" to 1,
                "FIELD_TIME_INVALID" to 1,
                "REQUIRED_FACT_UNRESOLVED" to 3,
            ),
            byCode,
        )
    }

    @Test
    fun metadataAreaNeverLeaksIntoOutputAndNonPersistedColumnsStayOutOfDiagnostics() {
        val result = WechatBillParser.parse(inputRef, workbookA())
        val outputStrings = result.rows.flatMap { row ->
            when (row) {
                is WechatRowResult.Accepted -> listOf(
                    row.facts.amountMinor.toString(), row.facts.currencyCode, row.facts.currencyPrecision.toString(),
                    row.facts.occurredAt, row.facts.directionToken, row.facts.statusToken ?: "",
                )
                is WechatRowResult.Rejected -> emptyList()
            } + row.diagnostics.flatMap { listOf(it.code, it.severity, it.scope, it.inputRef, it.recordOrdinal?.toString() ?: "", it.fieldRole ?: "") }
        }
        val forbidden = (0..16).flatMap { listOf("SYN-META-PII-EXPORT-$it", "SYN-META-PII-NICK-$it") } +
            listOf("SYN-SECRET-COUNTERPARTY", "SYN-SECRET-PRODUCT", "SYN-SECRET-METHOD", "SYN-SECRET-TXNO", "SYN-SECRET-MERCHNO", "SYN-SECRET-NOTE")
        forbidden.forEach { secret ->
            assertTrue(outputStrings.none { it == secret || it.contains(secret) }, "forbidden value leaked: $secret")
        }
    }

    @Test
    fun headerMismatchVariantsRejectTheBatchWithStructureMismatch() {
        // 缺列: only ten header cells.
        val missing = WorkbookBuilder().apply {
            metadataRows()
            headerVariant(WechatSourceTokens.HEADER_TOKENS.dropLast(1).mapIndexed { i, t -> i to t })
        }.bytes()
        val missingResult = WechatBillParser.parse("batch-p403-b1", missing)
        assertEquals(WechatBatchOutcome.REJECTED, missingResult.outcome)
        assertDiagnostic(assertIs(missingResult.diagnostic), "STRUCTURE_MISMATCH", "fatal", "structure", expectedInputRef = "batch-p403-b1")

        // 多列: twelve header cells.
        val extra = WorkbookBuilder().apply {
            metadataRows()
            headerVariant(WechatSourceTokens.HEADER_TOKENS.mapIndexed { i, t -> i to t } + (11 to "多余"))
        }.bytes()
        val extraResult = WechatBillParser.parse("batch-p403-b2", extra)
        assertEquals(WechatBatchOutcome.REJECTED, extraResult.outcome)
        assertDiagnostic(assertIs(extraResult.diagnostic), "STRUCTURE_MISMATCH", "fatal", "structure", expectedInputRef = "batch-p403-b2")

        // 错位: direction and amount headers swapped.
        val swapped = WorkbookBuilder().apply {
            metadataRows()
            headerVariant(
                WechatSourceTokens.HEADER_TOKENS.mapIndexed { i, t ->
                    i to when (i) {
                        4 -> "金额(元)"
                        5 -> "收/支"
                        else -> t
                    }
                },
            )
        }.bytes()
        val swappedResult = WechatBillParser.parse("batch-p403-b3", swapped)
        assertEquals(WechatBatchOutcome.REJECTED, swappedResult.outcome)
        assertDiagnostic(assertIs(swappedResult.diagnostic), "STRUCTURE_MISMATCH", "fatal", "structure", expectedInputRef = "batch-p403-b3")

        // 差一字: amount header token differs by one character.
        val offByOne = WorkbookBuilder().apply {
            metadataRows()
            headerVariant(
                WechatSourceTokens.HEADER_TOKENS.mapIndexed { i, t -> i to if (i == 5) "金额(圆)" else t },
            )
        }.bytes()
        val offByOneResult = WechatBillParser.parse("batch-p403-b4", offByOne)
        assertEquals(WechatBatchOutcome.REJECTED, offByOneResult.outcome)
        assertDiagnostic(assertIs(offByOneResult.diagnostic), "STRUCTURE_MISMATCH", "fatal", "structure", expectedInputRef = "batch-p403-b4")
    }

    @Test
    fun rowStructureVariantsRejectAtRecordLevelWhileTrailingEmptyCellsAreValid() {
        // 12-column data row.
        val tooWide = WorkbookBuilder().apply {
            metadataRows()
            header()
            dataRow(18, rowCells(num(serialOf(LocalDate.of(2026, 8, 1), 12, 30)), "商户消费", "支出", num(10.0), "支付成功") + (11 to TextSpec("多余列")))
        }.bytes()
        val tooWideResult = WechatBillParser.parse("batch-p403-f", tooWide)
        assertEquals(WechatBatchOutcome.PARTIAL, tooWideResult.outcome)
        val tooWideRow = rejected(tooWideResult.rows, 0)
        assertDiagnostic(tooWideRow.diagnostics.single(), "STRUCTURE_MISMATCH", "fatal", "record", 0, expectedInputRef = "batch-p403-f")

        // 10-column row missing a required column (col 7, 当前状态).
        val missingStatus = WorkbookBuilder().apply {
            metadataRows()
            header()
            dataRow(
                18,
                listOf(
                    0 to num(serialOf(LocalDate.of(2026, 8, 1), 12, 30)), 1 to TextSpec("商户消费"),
                    2 to TextSpec("SYN-SECRET-COUNTERPARTY"), 3 to TextSpec("SYN-SECRET-PRODUCT"),
                    4 to TextSpec("支出"), 5 to num(10.0), 6 to TextSpec("SYN-SECRET-METHOD"),
                    8 to TextSpec("SYN-SECRET-TXNO"), 9 to TextSpec("SYN-SECRET-MERCHNO"),
                ),
            )
        }.bytes()
        val missingStatusResult = WechatBillParser.parse("batch-p403-f", missingStatus)
        assertEquals(WechatBatchOutcome.PARTIAL, missingStatusResult.outcome)
        val missingStatusRow = rejected(missingStatusResult.rows, 0)
        assertDiagnostic(missingStatusRow.diagnostics.single(), "STRUCTURE_MISMATCH", "fatal", "record", 0, expectedInputRef = "batch-p403-f")

        // Trailing empty cells in columns 8/9/10 are valid empty values.
        val trailing = WorkbookBuilder().apply {
            metadataRows()
            header()
            dataRow(
                18,
                listOf(
                    0 to num(serialOf(LocalDate.of(2026, 8, 1), 12, 30)), 1 to TextSpec("商户消费"),
                    2 to TextSpec("SYN-SECRET-COUNTERPARTY"), 3 to TextSpec("SYN-SECRET-PRODUCT"),
                    4 to TextSpec("支出"), 5 to num(10.0), 6 to TextSpec("SYN-SECRET-METHOD"),
                    7 to TextSpec("支付成功"),
                ),
            )
        }.bytes()
        val trailingResult = WechatBillParser.parse("batch-p403-f", trailing)
        assertEquals(WechatBatchOutcome.COMPLETE, trailingResult.outcome)
        val trailingRow = accepted(trailingResult.rows, 0)
        assertEquals(ImportCompleteness.VALID_COMPLETE, trailingRow.completeness)
        assertEquals(100, trailingRow.facts.amountMinor)
    }

    @Test
    fun xlsmContainerIsRejectedAsUnsafeOrOverLimitWithContainerScope() {
        val valid = WorkbookBuilder().apply {
            metadataRows()
            header()
        }.bytes()
        val xlsm = addZipEntry(valid, "xl/vbaProject.bin", byteArrayOf(0, 1, 2, 3))
        val result = WechatBillParser.parse("batch-p403-c", xlsm)
        assertEquals(WechatBatchOutcome.REJECTED, result.outcome)
        assertDiagnostic(assertIs(result.diagnostic), "INPUT_UNSAFE_OR_OVER_LIMIT", "fatal", "container", expectedInputRef = "batch-p403-c")
    }

    @Test
    fun corruptAndUnsupportedContainersMapToFrozenInputCodes() {
        // Truncated zip: PK header with garbage.
        val corrupt = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 0x14, 0, 0, 0) + ByteArray(64) { 7 }
        val corruptResult = WechatBillParser.parse("batch-p403-d", corrupt)
        assertEquals(WechatBatchOutcome.REJECTED, corruptResult.outcome)
        assertDiagnostic(assertIs(corruptResult.diagnostic), "INPUT_DECODE_FAILED", "fatal", "input", expectedInputRef = "batch-p403-d")

        // Plain garbage is not a supported container.
        val garbage = "hello world, not a spreadsheet".toByteArray(Charsets.UTF_8)
        val garbageResult = WechatBillParser.parse("batch-p403-d", garbage)
        assertEquals(WechatBatchOutcome.REJECTED, garbageResult.outcome)
        assertDiagnostic(assertIs(garbageResult.diagnostic), "INPUT_DECODE_FAILED", "fatal", "input", expectedInputRef = "batch-p403-d")

        // OLE2 container (.xls-shaped) is an unsupported format.
        val ole2 = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte()) + ByteArray(64) { 0 }
        val ole2Result = WechatBillParser.parse("batch-p403-d", ole2)
        assertEquals(WechatBatchOutcome.REJECTED, ole2Result.outcome)
        assertDiagnostic(assertIs(ole2Result.diagnostic), "INPUT_UNSUPPORTED", "fatal", "input", expectedInputRef = "batch-p403-d")

        // Empty input is a decode failure.
        val emptyResult = WechatBillParser.parse("batch-p403-d", ByteArray(0))
        assertEquals(WechatBatchOutcome.REJECTED, emptyResult.outcome)
        assertDiagnostic(assertIs(emptyResult.diagnostic), "INPUT_DECODE_FAILED", "fatal", "input", expectedInputRef = "batch-p403-d")
    }

    @Test
    fun emptyRowsAreSkippedWithoutRenumberingOrdinals() {
        val builder = WorkbookBuilder()
        builder.metadataRows()
        builder.header()
        builder.dataRow(18, rowCells(num(serialOf(LocalDate.of(2026, 8, 1), 12, 30)), "商户消费", "支出", num(100.4), "支付成功"))
        // Row 19 is left empty (no row object at all).
        builder.dataRow(20, rowCells(num(serialOf(LocalDate.of(2026, 8, 5), 9, 0)), "扫二维码付款", "支出", num(12.5), "支付成功"))
        val result = WechatBillParser.parse("batch-p403-g", builder.bytes(mapOf("100.4" to "128.50")))

        assertEquals(WechatBatchOutcome.COMPLETE, result.outcome)
        assertEquals(2, result.rows.size)
        assertEquals(0, result.rows[0].recordOrdinal)
        assertEquals(2, result.rows[1].recordOrdinal)
        assertEquals(12850, accepted(result.rows, 0).facts.amountMinor)
        assertEquals(125, accepted(result.rows, 2).facts.amountMinor)
    }

    @Test
    fun amountPrecisionVectorsDeriveMinorUnitsFromExactCellText() {
        val builder = WorkbookBuilder()
        builder.metadataRows()
        builder.header()
        // "0", "0.0" and "0.00" (markers), "12.5" and "128.50".
        builder.dataRow(18, rowCells(num(serialOf(LocalDate.of(2026, 8, 1), 8, 0)), "商户消费", "支出", num(200.3), "支付成功"))
        builder.dataRow(19, rowCells(num(serialOf(LocalDate.of(2026, 8, 1), 8, 1)), "商户消费", "支出", num(100.1), "支付成功"))
        builder.dataRow(20, rowCells(num(serialOf(LocalDate.of(2026, 8, 1), 8, 2)), "商户消费", "支出", num(100.2), "支付成功"))
        builder.dataRow(21, rowCells(num(serialOf(LocalDate.of(2026, 8, 1), 8, 3)), "商户消费", "支出", num(12.5), "支付成功"))
        builder.dataRow(22, rowCells(num(serialOf(LocalDate.of(2026, 8, 1), 8, 4)), "商户消费", "支出", num(100.4), "支付成功"))
        val bytes = builder.bytes(mapOf("200.3" to "0", "100.1" to "0.0", "100.2" to "0.00", "100.4" to "128.50"))

        val result = WechatBillParser.parse(inputRef, bytes)
        assertEquals(0, accepted(result.rows, 0).facts.amountMinor)
        assertEquals(0, accepted(result.rows, 0).facts.currencyPrecision)
        assertEquals(0, accepted(result.rows, 1).facts.amountMinor)
        assertEquals(1, accepted(result.rows, 1).facts.currencyPrecision)
        assertEquals(0, accepted(result.rows, 2).facts.amountMinor)
        assertEquals(2, accepted(result.rows, 2).facts.currencyPrecision)
        assertEquals(125, accepted(result.rows, 3).facts.amountMinor)
        assertEquals(1, accepted(result.rows, 3).facts.currencyPrecision)
        assertEquals(12850, accepted(result.rows, 4).facts.amountMinor)
        assertEquals(2, accepted(result.rows, 4).facts.currencyPrecision)

        // Negative amounts are outside the frozen domain.
        val negative = WorkbookBuilder().apply {
            metadataRows()
            header()
            dataRow(18, rowCells(num(serialOf(LocalDate.of(2026, 8, 1), 8, 0)), "商户消费", "支出", num(-5.0), "支付成功"))
        }.bytes()
        val negativeResult = WechatBillParser.parse(inputRef, negative)
        val negativeRow = rejected(negativeResult.rows, 0)
        assertDiagnostic(negativeRow.diagnostics.single(), "FIELD_AMOUNT_INVALID", "record_error", "field", 0, "amount")
    }

    @Test
    fun emptyRowObjectWithNoCellsProducesNoRecordAndDoesNotRenumberOrdinals() {
        val builder = WorkbookBuilder()
        builder.metadataRows()
        builder.header()
        builder.dataRow(18, rowCells(num(serialOf(LocalDate.of(2026, 8, 1), 12, 30)), "商户消费", "支出", num(100.4), "支付成功"))
        builder.dataRow(20, rowCells(num(serialOf(LocalDate.of(2026, 8, 5), 9, 0)), "扫二维码付款", "支出", num(12.5), "支付成功"))
        // Inject an empty <row> element (row object present, width <= 0) between the
        // two data rows; POI writes 1-based r attributes, so the gap row is r="20".
        val bytes = rewriteZip(builder.bytes(mapOf("100.4" to "128.50"))) { entryName, content ->
            if (entryName == "xl/worksheets/sheet1.xml") {
                content.toString(Charsets.UTF_8)
                    .replace("<row r=\"21\">", "<row r=\"20\"/><row r=\"21\">")
                    .toByteArray(Charsets.UTF_8)
            } else {
                content
            }
        }
        val result = WechatBillParser.parse(inputRef, bytes)
        assertEquals(WechatBatchOutcome.COMPLETE, result.outcome)
        assertEquals(2, result.rows.size)
        assertEquals(0, result.rows[0].recordOrdinal)
        assertEquals(2, result.rows[1].recordOrdinal)
        assertEquals(12850, accepted(result.rows, 0).facts.amountMinor)
        assertEquals(125, accepted(result.rows, 2).facts.amountMinor)
    }

    @Test
    fun timeSerialVectorsConvertToFrozenOffsetIsoText() {
        val builder = WorkbookBuilder()
        builder.metadataRows()
        builder.header()
        builder.dataRow(18, rowCells(num(serialOf(LocalDate.of(2026, 8, 1), 12, 30)), "商户消费", "支出", num(10.0), "支付成功"))
        builder.dataRow(19, rowCells(num(0.0), "商户消费", "支出", num(10.0), "支付成功"))
        builder.dataRow(20, rowCells(num(61.0), "商户消费", "支出", num(10.0), "支付成功"))
        builder.dataRow(21, rowCells(num(-1.0), "商户消费", "支出", num(10.0), "支付成功"))
        builder.dataRow(22, rowCells(text("不是时间"), "商户消费", "支出", num(10.0), "支付成功"))
        val bytes = builder.bytes()

        val result = WechatBillParser.parse(inputRef, bytes)
        assertEquals("2026-08-01T12:30:00+08:00", accepted(result.rows, 0).facts.occurredAt)
        assertEquals("1899-12-30T00:00:00+08:00", accepted(result.rows, 1).facts.occurredAt)
        assertEquals("1900-03-01T00:00:00+08:00", accepted(result.rows, 2).facts.occurredAt)
        assertDiagnostic(rejected(result.rows, 3).diagnostics.single(), "FIELD_TIME_INVALID", "record_error", "field", 3, "occurred_at")
        assertDiagnostic(rejected(result.rows, 4).diagnostics.single(), "FIELD_TIME_INVALID", "record_error", "field", 4, "occurred_at")

        // Determinism: identical bytes always produce the identical result.
        assertEquals(result, WechatBillParser.parse(inputRef, bytes))
    }

    @Test
    fun oversizedInputAndZipBombMapToUnsafeOrOverLimit() {
        val oversized = ByteArray(WechatSourceTokens.MAX_INPUT_BYTES + 1)
        val oversizedResult = WechatBillParser.parse(inputRef, oversized)
        assertEquals(WechatBatchOutcome.REJECTED, oversizedResult.outcome)
        assertDiagnostic(assertIs(oversizedResult.diagnostic), "INPUT_UNSAFE_OR_OVER_LIMIT", "fatal", "input")

        // A zip whose sheet part expands far beyond its compressed size triggers the
        // untouched ZipSecureFile defaults during the POI open.
        val bomb = zipBombWorkbook()
        val bombResult = WechatBillParser.parse(inputRef, bomb)
        assertEquals(WechatBatchOutcome.REJECTED, bombResult.outcome)
        assertDiagnostic(assertIs(bombResult.diagnostic), "INPUT_UNSAFE_OR_OVER_LIMIT", "fatal", "container")
    }

    private fun zipBombWorkbook(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            listOf(
                "[Content_Types].xml" to
                    "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                    "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                    "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                    "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
                    "</Types>",
                "_rels/.rels" to
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
                    "</Relationships>",
                "xl/workbook.xml" to
                    "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
                    "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                    "<sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
                "xl/_rels/workbook.xml.rels" to
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
                    "</Relationships>",
            ).forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            zos.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zos.write(ByteArray(10 * 1024 * 1024))
            zos.closeEntry()
        }
        return out.toByteArray()
    }
}
