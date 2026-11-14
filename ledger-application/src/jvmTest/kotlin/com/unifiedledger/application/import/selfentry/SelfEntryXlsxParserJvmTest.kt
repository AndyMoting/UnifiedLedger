package com.unifiedledger.application.import.selfentry

import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportFundingState
import com.unifiedledger.application.ImportRecordKind
import com.unifiedledger.application.ImportSourceFacts
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * P8-04 i2 typed per-class evidence (spec section 4 i2; V-2 family, one assertion per class).
 *
 * The fixture strategy is the dispatch's builder contract: the base bytes are ALWAYS
 * [SelfEntryTemplateGenerator.generateTemplate] output, and every variant is produced by
 * STRING-EDITING the generated XML parts — the header `<row r="1">` is spliced verbatim from
 * the generated sheet (so no header string is ever hand-copied; the D-16 single-source
 * discipline holds in the tests too), data rows are appended with the generator's own
 * sharedStrings mechanics (one `<si>` per distinct string, `t="s"` index cells, exact-decimal
 * numeric `<v>` cells), and new strings are appended to the generated sharedStrings pool with
 * `count`/`uniqueCount` bumped. The happy path parses the PRISTINE generated template, so the
 * three example rows double as the round-trip fixture (V-1's i2 half).
 */
class SelfEntryXlsxParserJvmTest {
    private val inputRef = "pick-handle-p804-i2"

    // ---------------------------------------------------------------- happy path (V-1 i2 half, pristine template)

    @Test
    fun theGeneratedTemplateRoundTripsAllThreeTypesWithPinnedFacts() {
        val batch = SelfEntryXlsxParser.parse(inputRef, SelfEntryTemplateGenerator.generateTemplate())

        assertEquals(SelfEntryBatchOutcome.PARTIAL, batch.outcome, "the blank 币种 cells are visible inferences")
        assertNull(batch.diagnostic)
        assertEquals(3, batch.rows.size)
        val expense = assertIs<SelfEntryRowResult.Accepted>(batch.rows[0])
        assertEquals(0, expense.recordOrdinal)
        assertEquals(ImportRecordKind.ORDINARY_FLOW_SOURCE, expense.recordKind)
        assertEquals(
            factsOf(minor = 1234L, occurredAt = "2026-01-01T10:00:00+08:00", direction = "out"),
            expense.facts,
        )
        assertEquals(ImportCompleteness.VALID_INCOMPLETE, expense.completeness)
        assertEquals("REQUIRED_FACT_UNRESOLVED", expense.diagnostics.single().code)
        assertEquals("餐饮", expense.matchInputs.categoryPrimary)
        assertEquals("", expense.matchInputs.categorySecondary)
        assertEquals("现金", expense.matchInputs.accountPrimary)
        assertEquals("", expense.matchInputs.accountSecondary)
        assertEquals("示例：支出行，账户2 与 二级分类 留空", expense.noteText)
        val income = assertIs<SelfEntryRowResult.Accepted>(batch.rows[1])
        assertEquals(ImportRecordKind.ORDINARY_FLOW_SOURCE, income.recordKind)
        assertEquals(
            factsOf(minor = 5678L, occurredAt = "2026-01-02T11:30:00+08:00", direction = "in"),
            income.facts,
        )
        val transfer = assertIs<SelfEntryRowResult.Accepted>(batch.rows[2])
        assertEquals(ImportRecordKind.TRANSFER_FLOW_SOURCE, transfer.recordKind)
        assertEquals(
            factsOf(minor = 9012L, occurredAt = "2026-01-03T12:00:00+08:00", direction = "out"),
            transfer.facts,
        )
        assertEquals("还信用卡", transfer.matchInputs.categorySecondary)
        assertEquals("银行卡", transfer.matchInputs.accountSecondary)
        assertEquals("示例：转账行填写账户2", transfer.noteText)
    }

    @Test
    fun aTransferRowWithABlankAccount2IsTheMissingLegKindWithTheNoFundsPin() {
        val batch =
            SelfEntryXlsxParser.parse(
                inputRef,
                variantFile(
                    listOf(
                        row(
                            type = text(SelfEntrySourceTokens.TYPE_TRANSFER),
                            occurredAt = text("2026-02-01 08:00:00"),
                            amount = numeric("7"),
                            categoryPrimary = text("转账"),
                            accountPrimary = text("现金"),
                            note = text("缺账户2"),
                        ),
                    ),
                ),
            )

        val accepted = assertIs<SelfEntryRowResult.Accepted>(batch.rows.single())
        assertEquals(ImportRecordKind.TRANSFER_FLOW_SOURCE_MISSING_LEG, accepted.recordKind)
        assertEquals(
            factsOf(minor = 700L, occurredAt = "2026-02-01T08:00:00+08:00", direction = "out")
                .copy(fundingState = ImportFundingState.NO_FUNDS),
            accepted.facts,
        )
    }

    // ---------------------------------------------------------------- header drift: whole-batch rejection (D-14)

    @Test
    fun aHeaderMissingItsLastColumnIsAWholeBatchRejection() {
        val batch = SelfEntryXlsxParser.parse(inputRef, headerVariant(dropLastHeaderCell = true))

        assertWholeBatchRejected(batch)
    }

    @Test
    fun aHeaderWithAnExtraTenthColumnIsAWholeBatchRejection() {
        val batch = SelfEntryXlsxParser.parse(inputRef, headerVariant(extraTenthCell = true))

        assertWholeBatchRejected(batch)
    }

    @Test
    fun aReorderedHeaderIsAWholeBatchRejection() {
        val batch = SelfEntryXlsxParser.parse(inputRef, headerVariant(swapFirstTwoCells = true))

        assertWholeBatchRejected(batch)
    }

    @Test
    fun aRewrittenHeaderTextIsAWholeBatchRejection() {
        val batch = SelfEntryXlsxParser.parse(inputRef, headerVariant(rewriteLastHeaderText = true))

        assertWholeBatchRejected(batch)
    }

    // ---------------------------------------------------------------- row-level rejects (D-16.1/D-16.2/D-9/D-16.3)

    @Test
    fun anUnknownTypeTokenIsARowRejectOnTheTypeField() {
        val batch = SelfEntryXlsxParser.parse(inputRef, singleRowFile(type = text("退款")))

        assertRowRejected(batch, ordinal = 0, code = "REQUIRED_FACT_MISSING", fieldRole = "type")
    }

    @Test
    fun aMissingTypeCellIsARowRejectOnTheTypeField() {
        // The row itself must be present (other mandatory cells filled), or the parser would
        // legitimately skip it as an empty row before the type gate is reached.
        val batch =
            SelfEntryXlsxParser.parse(
                inputRef,
                singleRowFile(
                    occurredAt = text("2026-02-01 08:00:00"),
                    amount = numeric("12.34"),
                ),
            )

        assertRowRejected(batch, ordinal = 0, code = "REQUIRED_FACT_MISSING", fieldRole = "type")
    }

    @Test
    fun aNumericDatetimeCellIsARowRejectNeverSerialConverted() {
        val batch =
            SelfEntryXlsxParser.parse(
                inputRef,
                singleRowFile(
                    type = text(SelfEntrySourceTokens.TYPE_EXPENSE),
                    occurredAt = numeric("46023.42"),
                ),
            )

        assertRowRejected(batch, ordinal = 0, code = "SPINE_SELF_ENTRY_DATETIME_NOT_TEXTUAL", fieldRole = "occurred_at")
    }

    @Test
    fun aWrongShapedDatetimeTextIsARowReject() {
        val batch =
            SelfEntryXlsxParser.parse(
                inputRef,
                singleRowFile(
                    type = text(SelfEntrySourceTokens.TYPE_EXPENSE),
                    occurredAt = text("2026/02/01 08:00:00"),
                ),
            )

        assertRowRejected(batch, ordinal = 0, code = "FIELD_TIME_INVALID", fieldRole = "occurred_at")
    }

    @Test
    fun anAmountBeyondTheCurrencyPrecisionIsThePrecisionRowReject() {
        val batch =
            SelfEntryXlsxParser.parse(
                inputRef,
                singleRowFile(
                    type = text(SelfEntrySourceTokens.TYPE_EXPENSE),
                    // The earlier mandatory gates (类型 above, 日期时间 here) must pass so the
                    // row actually reaches the precision gate.
                    occurredAt = text("2026-02-01 08:00:00"),
                    amount = numeric("12.345"),
                ),
            )

        assertRowRejected(batch, ordinal = 0, code = "SELF_ENTRY_AMOUNT_PRECISION", fieldRole = "amount")
    }

    @Test
    fun aNegativeAmountOnAnExpenseRowIsTheDirectionConflictRowReject() {
        val batch =
            SelfEntryXlsxParser.parse(
                inputRef,
                singleRowFile(
                    type = text(SelfEntrySourceTokens.TYPE_EXPENSE),
                    // The mandatory datetime gate must pass so the row reaches the sign gate.
                    occurredAt = text("2026-02-01 08:00:00"),
                    amount = numeric("-12.34"),
                ),
            )

        assertRowRejected(batch, ordinal = 0, code = "SELF_ENTRY_DIRECTION_CONFLICT", fieldRole = "amount")
    }

    @Test
    fun aNegativeAmountOnAnIncomeRowIsTheSameDirectionConflictRowReject() {
        // D-9 review F2: the sign gate covers BOTH directed rows — a negative magnitude on a
        // 收入 row claims an expense the 类型 denies, the mirror of the 支出 case; silently
        // absorbing it would be the forbidden re-direction.
        val batch =
            SelfEntryXlsxParser.parse(
                inputRef,
                singleRowFile(
                    type = text(SelfEntrySourceTokens.TYPE_INCOME),
                    // The mandatory datetime gate must pass so the row reaches the sign gate.
                    occurredAt = text("2026-02-01 08:00:00"),
                    amount = numeric("-12.34"),
                ),
            )

        assertRowRejected(batch, ordinal = 0, code = "SELF_ENTRY_DIRECTION_CONFLICT", fieldRole = "amount")
    }

    // ---------------------------------------------------------------- D-5 currency: the settled incomplete outcome

    @Test
    fun aNonDefaultCurrencyRidesTheIncompleteCandidatePathWithTheRawCodeCarried() {
        val batch =
            SelfEntryXlsxParser.parse(
                inputRef,
                singleRowFile(
                    type = text(SelfEntrySourceTokens.TYPE_EXPENSE),
                    // The mandatory datetime gate must pass so the row reaches the D-5 branch.
                    occurredAt = text("2026-02-01 08:00:00"),
                    amount = numeric("12.34"),
                    currency = text("USD"),
                ),
            )

        val accepted = assertIs<SelfEntryRowResult.Accepted>(batch.rows.single())
        assertEquals(ImportCompleteness.VALID_INCOMPLETE, accepted.completeness)
        assertEquals("USD", accepted.facts.currencyCode)
        assertEquals("REQUIRED_FACT_UNRESOLVED", accepted.diagnostics.single().code)
    }

    // ---------------------------------------------------------------- empty rows / header-only file

    @Test
    fun anEmptyRowIsSkippedWithoutRenumbering() {
        val batch =
            SelfEntryXlsxParser.parse(
                inputRef,
                variantFile(
                    listOf(
                        // sheet row 2 = ordinal 0: a present row element with no cells.
                        listOf(
                            absent(),
                            absent(),
                            absent(),
                            absent(),
                            absent(),
                            absent(),
                            absent(),
                            absent(),
                            absent(),
                        ),
                        // sheet row 3 = ordinal 1: the only real record proves no renumbering.
                        row(
                            type = text(SelfEntrySourceTokens.TYPE_INCOME),
                            occurredAt = text("2026-02-02 09:30:00"),
                            amount = numeric("3.50"),
                            categoryPrimary = text("工资"),
                            accountPrimary = text("银行卡"),
                        ),
                    ),
                ),
            )

        // The skipped empty row keeps its slot: the row at r="3" (0-based 2, ordinal
        // 2 - FIRST_DATA_ROW_INDEX = 1) stays ordinal 1 instead of compacting to 0.
        assertEquals(listOf(1), batch.rows.map { it.recordOrdinal })
    }

    @Test
    fun aHeaderOnlyFileParsesToZeroRecordsCleanly() {
        val batch = SelfEntryXlsxParser.parse(inputRef, variantFile(emptyList()))

        assertEquals(SelfEntryBatchOutcome.COMPLETE, batch.outcome)
        assertEquals(emptyList(), batch.rows)
        assertNull(batch.diagnostic)
    }

    // ---------------------------------------------------------------- the variant builder (string-edited generated parts)

    private fun factsOf(
        minor: Long,
        occurredAt: String,
        direction: String,
    ): ImportSourceFacts =
        ImportSourceFacts(
            amountMinor = minor,
            currencyCode = "CNY",
            currencyPrecision = 2,
            occurredAt = occurredAt,
            directionToken = direction,
            statusToken = "settled",
            fundingState = ImportFundingState.SETTLED,
            fundingRuleId = "legacy-settled-v1",
            fundingRuleVersion = 1,
        )

    private fun assertWholeBatchRejected(batch: SelfEntryBatchResult) {
        assertEquals(SelfEntryBatchOutcome.REJECTED, batch.outcome)
        assertEquals(emptyList(), batch.rows)
        assertEquals("STRUCTURE_MISMATCH", batch.diagnostic?.code)
        assertEquals("fatal", batch.diagnostic?.severity)
        assertEquals("structure", batch.diagnostic?.scope)
    }

    private fun assertRowRejected(
        batch: SelfEntryBatchResult,
        ordinal: Int,
        code: String,
        fieldRole: String,
    ) {
        assertEquals(SelfEntryBatchOutcome.PARTIAL, batch.outcome)
        val rejected = assertIs<SelfEntryRowResult.Rejected>(batch.rows.single())
        assertEquals(ordinal, rejected.recordOrdinal)
        assertEquals(code, rejected.diagnostics.single().code)
        assertEquals(fieldRole, rejected.diagnostics.single().fieldRole)
    }

    /** One variant cell: a shared string, an exact raw numeric, or an absent cell. */
    private sealed interface VariantCell {
        class Text(
            val text: String,
        ) : VariantCell

        class Numeric(
            val raw: String,
        ) : VariantCell

        data object Absent : VariantCell
    }

    private fun text(value: String): VariantCell = VariantCell.Text(value)

    private fun numeric(raw: String): VariantCell = VariantCell.Numeric(raw)

    private fun absent(): VariantCell = VariantCell.Absent

    /** A 9-column data row; trailing absent cells simply render as nothing. */
    private fun row(
        type: VariantCell = absent(),
        occurredAt: VariantCell = absent(),
        amount: VariantCell = absent(),
        currency: VariantCell = absent(),
        categoryPrimary: VariantCell = absent(),
        categorySecondary: VariantCell = absent(),
        accountPrimary: VariantCell = absent(),
        accountSecondary: VariantCell = absent(),
        note: VariantCell = absent(),
    ): List<VariantCell> =
        listOf(
            type,
            occurredAt,
            amount,
            currency,
            categoryPrimary,
            categorySecondary,
            accountPrimary,
            accountSecondary,
            note,
        )

    /** One data row on sheet row 2 behind the pristine header (same named cells as [row]). */
    private fun singleRowFile(
        type: VariantCell = absent(),
        occurredAt: VariantCell = absent(),
        amount: VariantCell = absent(),
        currency: VariantCell = absent(),
        categoryPrimary: VariantCell = absent(),
        categorySecondary: VariantCell = absent(),
        accountPrimary: VariantCell = absent(),
        accountSecondary: VariantCell = absent(),
        note: VariantCell = absent(),
    ): ByteArray =
        variantFile(
            listOf(
                row(
                    type,
                    occurredAt,
                    amount,
                    currency,
                    categoryPrimary,
                    categorySecondary,
                    accountPrimary,
                    accountSecondary,
                    note,
                ),
            ),
        )

    /**
     * A variant package: the generated six parts with the sheet's data rows replaced by the
     * given rows (the generated header `<row>` spliced verbatim) and any new cell strings
     * appended to the generated sharedStrings pool.
     */
    private fun variantFile(rows: List<List<VariantCell>>): ByteArray {
        val parts = zipParts(SelfEntryTemplateGenerator.generateTemplate())
        val sheetXml = parts.getValue(SHEET_PART).toString(Charsets.UTF_8)
        val sharedStringsXml = parts.getValue(SHARED_STRINGS_PART).toString(Charsets.UTF_8)

        val headerRow = sheetXml.substringAfter("<sheetData>").substringBefore("</row>") + "</row>"
        val pool = LinkedHashMap<String, Int>()
        sharedStringTexts(sharedStringsXml).forEachIndexed { index, item -> pool[item] = index }
        val originalCount = pool.size
        val dataRows =
            rows
                .mapIndexed { index, cells -> renderRow(index + 2, cells, pool) }
                .joinToString("")
        val newSheet =
            sheetXml.substringBefore("<sheetData>") +
                "<sheetData>" +
                headerRow +
                dataRows +
                "</sheetData>" +
                sheetXml.substringAfter("</sheetData>")
        val additions =
            pool.entries
                .drop(originalCount)
                .joinToString("") { "<si><t>${escapeXml(it.key)}</t></si>" }
        val newSharedStrings =
            sharedStringsXml
                .replace("</sst>", additions + "</sst>")
                .replace("count=\"$originalCount\"", "count=\"${pool.size}\"")
                .replace("uniqueCount=\"$originalCount\"", "uniqueCount=\"${pool.size}\"")
        parts[SHEET_PART] = newSheet.toByteArray(Charsets.UTF_8)
        parts[SHARED_STRINGS_PART] = newSharedStrings.toByteArray(Charsets.UTF_8)
        return rezip(parts)
    }

    /** A variant whose sheet keeps the pristine example rows but carries an edited header row. */
    private fun headerVariant(
        dropLastHeaderCell: Boolean = false,
        extraTenthCell: Boolean = false,
        swapFirstTwoCells: Boolean = false,
        rewriteLastHeaderText: Boolean = false,
    ): ByteArray {
        val parts = zipParts(SelfEntryTemplateGenerator.generateTemplate())
        val sheetXml = parts.getValue(SHEET_PART).toString(Charsets.UTF_8)
        val headerRow = sheetXml.substringAfter("<sheetData>").substringBefore("</row>") + "</row>"
        val pool = sharedStringTexts(parts.getValue(SHARED_STRINGS_PART).toString(Charsets.UTF_8))
        var edited = headerRow
        when {
            dropLastHeaderCell -> edited = edited.replace(lastHeaderCellXml(headerRow), "")
            extraTenthCell ->
                edited =
                    edited.replace(
                        "</row>",
                        "<c r=\"J1\" t=\"s\"><v>${pool.indexOf(SelfEntryTemplateGenerator.HEADER_TYPE)}</v></c></row>",
                    )
            swapFirstTwoCells -> {
                // Swap the shared-string INDICES between A1 and B1, keeping each cell's own
                // `r` reference: the reader resolves columns by `r`, so swapping whole `<c>`
                // elements would put the strings right back and never drift the header. The
                // index replacement anchors on the full `<v>…</v>` token — a bare-number
                // replace would also corrupt the digit inside the r="B1" reference.
                val first = firstHeaderCellXml(headerRow)
                val second = secondHeaderCellXml(headerRow)
                val firstValue = first.substringAfter("<v>").substringBefore("</v>")
                val secondValue = second.substringAfter("<v>").substringBefore("</v>")
                edited =
                    edited
                        .replace(first, first.replace("<v>$firstValue</v>", "<v>$secondValue</v>"))
                        .replace(second, second.replace("<v>$secondValue</v>", "<v>$firstValue</v>"))
            }
            rewriteLastHeaderText ->
                edited =
                    edited.replace(
                        lastHeaderCellXml(headerRow),
                        "<c r=\"I1\" t=\"s\"><v>${pool.indexOf(SelfEntryTemplateGenerator.HEADER_TYPE)}</v></c>",
                    )
        }
        parts[SHEET_PART] =
            sheetXml
                .replace(headerRow, edited)
                .toByteArray(Charsets.UTF_8)
        return rezip(parts)
    }

    // The generated header cells are contiguous, so the first/second/last cell substrings
    // are located positionally and edited without touching any other part of the sheet.
    private fun firstHeaderCellXml(headerRow: String): String = headerRow.substringAfter("<row r=\"1\">").substringBefore("</c>") + "</c>"

    private fun secondHeaderCellXml(headerRow: String): String =
        headerRow
            .substringAfter(firstHeaderCellXml(headerRow))
            .substringBefore("</c>") + "</c>"

    private fun lastHeaderCellXml(headerRow: String): String = "<c r=\"I1\" t=\"s\"><v>${headerRow.substringAfterLast("<v>").substringBefore("</v>")}</v></c>"

    private fun renderRow(
        rowIndex: Int,
        cells: List<VariantCell>,
        pool: LinkedHashMap<String, Int>,
    ): String =
        "<row r=\"$rowIndex\">" +
            cells
                .mapIndexed { column, cell -> renderCell(columnLetter(column) + rowIndex, cell, pool) }
                .joinToString("") +
            "</row>"

    private fun renderCell(
        reference: String,
        cell: VariantCell,
        pool: LinkedHashMap<String, Int>,
    ): String =
        when (cell) {
            is VariantCell.Text -> {
                val index = pool.getOrPut(cell.text) { pool.size }
                "<c r=\"$reference\" t=\"s\"><v>$index</v></c>"
            }
            is VariantCell.Numeric -> "<c r=\"$reference\"><v>${cell.raw}</v></c>"
            VariantCell.Absent -> ""
        }

    private fun columnLetter(columnIndex: Int): String = ('A' + columnIndex).toString()

    private fun sharedStringTexts(sharedStringsXml: String): List<String> =
        sharedStringsXml
            .substringBefore("</sst>")
            .split("<si><t>")
            .drop(1)
            .map { it.substringBefore("</t>") }

    private fun escapeXml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private fun zipParts(bytes: ByteArray): MutableMap<String, ByteArray> {
        val parts = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                parts[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return parts
    }

    private fun rezip(parts: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            parts.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private companion object {
        const val SHEET_PART = "xl/worksheets/sheet1.xml"
        const val SHARED_STRINGS_PART = "xl/sharedStrings.xml"
    }
}
