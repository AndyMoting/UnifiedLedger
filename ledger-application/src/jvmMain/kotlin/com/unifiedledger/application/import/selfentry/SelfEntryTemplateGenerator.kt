package com.unifiedledger.application.import.selfentry

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * P8-04 i1 (D-11/D-17; spec `docs/specs/2026-11-14-p8-04-self-entry-import-design.md`): the
 * runtime generator of the SELF_ENTRY_XLSX import template.
 *
 * D-11 rules the template is GENERATED at runtime, never shipped as a static asset: the
 * generator lives in jvmMain (shared by Android and desktop, like every import arm), so the
 * template's shape and the code that parses it can never drift apart — there is no asset to
 * fall behind a parser change, no resource lookup to fail on a platform, and the version the
 * user downloads is by construction the version this build understands (the zero-asset-drift
 * rationale; spec section 3 D-11 and section 6 item 3).
 *
 * The output is the six-part minimal OOXML package of D-17, kept strictly inside the frozen
 * minimal semantic face of `BoundedXlsxReader` (jvmMain `import/wechat/`, KDoc lines 19-73 —
 * that KDoc is the contract this generator writes against):
 *
 * - parts: `[Content_Types].xml`, `_rels/.rels`, `xl/workbook.xml`,
 *   `xl/_rels/workbook.xml.rels`, `xl/sharedStrings.xml`, `xl/worksheets/sheet1.xml` — exactly
 *   the parts the reader resolves (package rel -> workbook part; workbook rels -> first sheet +
 *   shared strings);
 * - ONE sheet ([SHEET_NAME]); the workbook's first sheet carries the `r:id` the reader resolves
 *   through the workbook rels;
 * - default namespaces WITHOUT prefixes on every part: the reader matches elements by qName and
 *   a document whose elements carry namespace prefixes is outside its minimal face (reader KDoc
 *   lines 67-72);
 * - every text cell is a shared-string cell (`t="s"` over the sharedStrings index) — the
 *   uniform shape the reader resolves through `parseSharedStrings` (`<si>` items, runs
 *   concatenated); no inline strings, no formulas (`<f>` never emitted, so no cell is a FORMULA
 *   cell), no styles (no `styles.xml` part, no `s=` attributes), no rich text;
 * - every `<row>`/`<c>` carries its A1 `r` reference (`A1`..`I4`), so the reader never falls
 *   back to sequential positioning (reader lines 476-508: an explicit reference wins; the
 *   fallback exists for exports that omit it — the template does not omit it);
 * - numbers (the example amounts) are numeric cells (`t` absent) whose `<v>` is the EXACT
 *   decimal text — the reader returns that cached text verbatim as [Cell.rawValue] and never
 *   routes it through binary floating point (reader line 103); the generator therefore never
 *   serializes a float/double, only the literal decimal strings below;
 * - the XML declaration of every part is UTF-8, and every string is XML-escaped through
 *   [escapeXml] (attributes and text both use the five predefined entities; nothing else in the
 *   emitted markup is input-derived, so no CDATA/section handling is needed).
 *
 * ROUND-TRIP INVARIANT (V-1): the i2 parser reads this generator's output back through the
 * same [BoundedXlsxReader] face, and BOTH sides reference the header strings from the public
 * constants below (D-16: 生成器与解析器同源引用，单一常量源). The parser must not hand-copy a
 * header string and the generator must not rename one unilaterally: a renamed header is a
 * column-contract change and takes the D-14 versioning path (format id bump / new file name),
 * never a silent edit of one side.
 */
object SelfEntryTemplateGenerator {
    /** 第 1 列表头：类型（支出|收入|转账 三值，D-16.1）。 */
    const val HEADER_TYPE: String = "类型"

    /** 第 2 列表头：日期时间（文本 `yyyy-MM-dd HH:mm:ss`，D-16.2）。 */
    const val HEADER_OCCURRED_AT: String = "日期时间"

    /** 第 3 列表头：金额（带符号十进制文本，BigDecimal 文本解析，D-16.3）。 */
    const val HEADER_AMOUNT: String = "金额"

    /** 第 4 列表头：币种（空 = 账本默认币种，D-5/D-16.4）。 */
    const val HEADER_CURRENCY: String = "币种"

    /** 第 5 列表头：一级分类（名称匹配输入，D-16.5）。 */
    const val HEADER_CATEGORY_PRIMARY: String = "一级分类"

    /** 第 6 列表头：二级分类（空 = 不适用；转账行示范用法，D-16.6）。 */
    const val HEADER_CATEGORY_SECONDARY: String = "二级分类"

    /** 第 7 列表头：账户1（资金腿；转账行的来源腿，D-16.7）。 */
    const val HEADER_ACCOUNT_PRIMARY: String = "账户1"

    /** 第 8 列表头：账户2（仅转账行填写，缺格 = missing_leg 候选，D-4/D-16.8）。 */
    const val HEADER_ACCOUNT_SECONDARY: String = "账户2"

    /** 第 9 列表头：备注（仅候选详情展示位，零持久化，D-7/D-16.9）。 */
    const val HEADER_NOTE: String = "备注"

    /** The 9 frozen header strings, in column order A..I (D-2/D-16; the single constant source). */
    val HEADERS: List<String> =
        listOf(
            HEADER_TYPE,
            HEADER_OCCURRED_AT,
            HEADER_AMOUNT,
            HEADER_CURRENCY,
            HEADER_CATEGORY_PRIMARY,
            HEADER_CATEGORY_SECONDARY,
            HEADER_ACCOUNT_PRIMARY,
            HEADER_ACCOUNT_SECONDARY,
            HEADER_NOTE,
        )

    /** D-14: the SAF CreateDocument suggested name carries the template version. */
    const val TEMPLATE_FILE_NAME: String = "unifiedledger-import-template-v1.xlsx"

    /** The workbook's single sheet name (D-17: 单 sheet; display copy only — the reader resolves the first sheet by relationship, not name). */
    const val SHEET_NAME: String = "导入模板"

    /** The 3 example rows (D-17): 支出 / 收入 / 转账， covering the D-16 forms (转账 fills 账户2 and the 二级分类; the others leave both patterns' non-applicable cells empty). */
    private val EXAMPLE_ROWS: List<List<String>> =
        listOf(
            listOf("支出", "2026-01-01 10:00:00", "12.34", "", "餐饮", "", "现金", "", "示例：支出行，账户2 与 二级分类 留空"),
            listOf("收入", "2026-01-02 11:30:00", "56.78", "", "工资", "", "银行卡", "", "示例：收入行，账户2 留空"),
            listOf("转账", "2026-01-03 12:00:00", "90.12", "", "转账", "还信用卡", "现金", "银行卡", "示例：转账行填写账户2"),
        )

    /** Generates the D-17 six-part minimal OOXML template as a zip (DEFLATED) byte array. */
    fun generateTemplate(): ByteArray {
        val sharedStrings = buildSharedStrings()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            writePart(zip, "[Content_Types].xml", contentTypesXml())
            writePart(zip, "_rels/.rels", rootRelsXml())
            writePart(zip, "xl/workbook.xml", workbookXml())
            writePart(zip, "xl/_rels/workbook.xml.rels", workbookRelsXml())
            writePart(zip, "xl/sharedStrings.xml", sharedStringsXml(sharedStrings))
            writePart(zip, "xl/worksheets/sheet1.xml", sheetXml(sharedStrings))
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ parts

    /** `[Content_Types].xml`: the defaults plus one Override per part that has no mapped extension. */
    private fun contentTypesXml(): String =
        buildString {
            append(XML_DECLARATION)
            append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
            append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
            append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
            append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
            append("<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
            append("<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>")
            append("</Types>")
        }

    /** `_rels/.rels`: the officeDocument relationship to the workbook part (the reader's first resolution). */
    private fun rootRelsXml(): String =
        buildString {
            append(XML_DECLARATION)
            append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
            append(
                "<Relationship Id=\"rId1\" " +
                    "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" " +
                    "Target=\"xl/workbook.xml\"/>",
            )
            append("</Relationships>")
        }

    /** `xl/workbook.xml`: one sheet, default namespace without prefixes, the `r:id` the reader resolves. */
    private fun workbookXml(): String =
        buildString {
            append(XML_DECLARATION)
            append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" ")
            append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
            append("<sheets><sheet name=\"").append(escapeXml(SHEET_NAME))
            append("\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>")
        }

    /** `xl/_rels/workbook.xml.rels`: the first worksheet and the shared-strings part. */
    private fun workbookRelsXml(): String =
        buildString {
            append(XML_DECLARATION)
            append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
            append(
                "<Relationship Id=\"rId1\" " +
                    "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" " +
                    "Target=\"worksheets/sheet1.xml\"/>",
            )
            append(
                "<Relationship Id=\"rId2\" " +
                    "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" " +
                    "Target=\"sharedStrings.xml\"/>",
            )
            append("</Relationships>")
        }

    /** `xl/sharedStrings.xml`: one `<si>` per distinct string; the reader concatenates each item's `<t>` runs. */
    private fun sharedStringsXml(strings: List<String>): String =
        buildString {
            append(XML_DECLARATION)
            append(
                "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"",
            )
            append(strings.size)
            append("\" uniqueCount=\"")
            append(strings.size)
            append("\">")
            strings.forEach { string ->
                append("<si><t>")
                append(escapeXml(string))
                append("</t></si>")
            }
            append("</sst>")
        }

    /**
     * `xl/worksheets/sheet1.xml`: header row 1 = the 9 frozen strings as shared-string cells,
     * then the 3 example rows. Example amounts are NUMERIC cells whose `<v>` is the exact
     * decimal text (no float ever enters the serialization); every other example cell is a
     * shared-string cell. Every row/cell carries its explicit A1 `r` reference.
     */
    private fun sheetXml(strings: List<String>): String {
        val index = strings.withIndex().associate { (i, value) -> value to i }
        return buildString {
            append(XML_DECLARATION)
            append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
            append("<sheetData>")
            appendRow(this, rowIndex = 1, values = HEADERS, index = index)
            EXAMPLE_ROWS.forEachIndexed { exampleIndex, row ->
                appendRow(this, rowIndex = exampleIndex + 2, values = row, index = index)
            }
            append("</sheetData></worksheet>")
        }
    }

    /** Appends one `<row>` with explicit A1 cell references; amounts land as exact-text numeric cells. */
    private fun appendRow(
        out: StringBuilder,
        rowIndex: Int,
        values: List<String>,
        index: Map<String, Int>,
    ) {
        out.append("<row r=\"")
        out.append(rowIndex)
        out.append("\">")
        values.forEachIndexed { columnIndex, value ->
            val reference = cellReference(rowIndex, columnIndex)
            if (rowIndex > 1 && columnIndex == AMOUNT_COLUMN) {
                // D-16.3: the amount is decimal text by contract; the generator writes the
                // literal string into <v> verbatim (never a binary float), so the reader hands
                // the parser the exact decimal text the template demonstrated.
                out
                    .append("<c r=\"")
                    .append(reference)
                    .append("\"><v>")
                    .append(escapeXml(value))
                    .append("</v></c>")
            } else {
                out
                    .append("<c r=\"")
                    .append(reference)
                    .append("\" t=\"s\"><v>")
                    .append(index.getValue(value))
                    .append("</v></c>")
            }
        }
        out.append("</row>")
    }

    /** The distinct string pool in first-use order: header row first, then the example rows' strings. */
    private fun buildSharedStrings(): List<String> {
        val pool = LinkedHashSet<String>()
        pool.addAll(HEADERS)
        EXAMPLE_ROWS.forEach { row ->
            row.forEachIndexed { columnIndex, value ->
                if (columnIndex != AMOUNT_COLUMN) pool.add(value)
            }
        }
        return pool.toList()
    }

    // ------------------------------------------------------------------ helpers

    /** The example amount column (0-based; column C). */
    private const val AMOUNT_COLUMN = 2

    /** The UTF-8 XML declaration every part opens with (D-17). */
    private const val XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"

    /** 0-based column index -> spreadsheet letters (0 = A, 25 = Z, 26 = AA). */
    private fun columnName(columnIndex: Int): String {
        var remaining = columnIndex
        val letters = StringBuilder()
        while (remaining >= 0) {
            letters.append(('A' + remaining % 26))
            remaining = remaining / 26 - 1
        }
        return letters.reverse().toString()
    }

    /** A1 reference for a (1-based row, 0-based column) pair, e.g. (1, 0) = "A1", (4, 8) = "I4". */
    private fun cellReference(
        rowIndex: Int,
        columnIndex: Int,
    ): String = columnName(columnIndex) + rowIndex

    /** XML-escapes text/attribute content with the five predefined entities (UTF-8 needs nothing else). */
    private fun escapeXml(value: String): String =
        buildString(value.length) {
            value.forEach { character ->
                when (character) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(character)
                }
            }
        }

    /** Writes one zip entry deflated, timestamped at the DOS epoch (reproducible bytes, no clock noise). */
    private fun writePart(
        zip: ZipOutputStream,
        name: String,
        content: String,
    ) {
        val entry = ZipEntry(name)
        entry.time = 0L
        zip.putNextEntry(entry)
        zip.write(content.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
    }
}
