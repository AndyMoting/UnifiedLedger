package com.unifiedledger.application.import.selfentry

import com.unifiedledger.application.import.wechat.BoundedXlsxReader
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.w3c.dom.Element
import org.xml.sax.SAXParseException
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P8-04 i1 structural + round-trip-half evidence (spec section 4 i1; V-1 first half).
 *
 * The generator's output is pinned on three independent layers:
 *
 * 1. CONTAINER: the zip holds exactly the D-17 six parts (no docProps, no styles — the minimal
 *    package) and every text part is namespace-aware-DOM well-formed under the reader's
 *    hardening config (DOCTYPE disallowed, both external-entity features off — the
 *    `BoundedXlsxReader.hardenSaxFactory` discipline mirrored through DocumentBuilderFactory).
 * 2. STRUCTURE: one sheet named [SelfEntryTemplateGenerator.SHEET_NAME]; the sharedStrings pool
 *    carries the 9 frozen headers — the D-16 single constant source, byte-identical through the
 *    reader — plus the example rows' strings, with `count`/`uniqueCount` agreeing with the pool;
 *    every row/cell carries its explicit A1 `r` reference (the reader's coordinate contract,
 *    lines 476-508); the example amounts are the LITERAL decimal texts in the sheet XML
 *    (AGENTS: money is exact — never a binary float) and no `<f>` exists (D-17: no formulas).
 * 3. ROUND TRIP (V-1 first half): the bytes re-open through [BoundedXlsxReader.read] — the
 *    same minimal-face reader the i2 parser will sit on — and every cell resolves with the
 *    exact semantics (STRING via sharedStrings, NUMERIC via the exact cached `<v>` text). POI
 *    XSSF (the jvmTest-only dependency, D-12) opens the same package as the Excel/WPS
 *    compatibility control (V-1's editor half; device evidence stays V-6's duty).
 */
class SelfEntryTemplateGeneratorJvmTest {
    private val templateBytes: ByteArray = SelfEntryTemplateGenerator.generateTemplate()

    // ---------------------------------------------------------------- container

    @Test
    fun thePackageHoldsExactlyTheSixMinimalParts() {
        assertContentEquals(
            listOf(
                "[Content_Types].xml",
                "_rels/.rels",
                "xl/workbook.xml",
                "xl/_rels/workbook.xml.rels",
                "xl/sharedStrings.xml",
                "xl/worksheets/sheet1.xml",
            ),
            zipPartNames(templateBytes),
        )
    }

    @Test
    fun everyTextPartIsWellFormedUnderTheReaderHardeningConfig() {
        zipParts(templateBytes).forEach { (name, bytes) ->
            val document = parseHardened(name, bytes)
            assertNull(document.doctype, "no DTD may exist in a generated part: $name")
            assertTrue(document.documentElement.childNodes.length > 0, "$name must have a root with children")
        }
    }

    @Test
    fun noPartCarriesADoctypeOrEntityDeclaration() {
        zipParts(templateBytes).forEach { (name, bytes) ->
            val text = bytes.toString(Charsets.UTF_8)
            assertTrue(!text.contains("<!DOCTYPE"), "$name must not carry a DOCTYPE")
            assertTrue(!text.contains("<!ENTITY"), "$name must not declare an entity")
        }
    }

    // ---------------------------------------------------------------- workbook / shared strings

    @Test
    fun theWorkbookDeclaresExactlyOneSheetWithTheReaderResolvableRelationship() {
        val document = parseHardened("xl/workbook.xml", zipParts(templateBytes).getValue("xl/workbook.xml"))
        val sheets = document.getElementsByTagName("sheet")
        assertEquals(1, sheets.length)
        val sheet = sheets.item(0)
        assertEquals(SelfEntryTemplateGenerator.SHEET_NAME, sheet.attributes.getNamedItem("name").nodeValue)
        assertEquals("1", sheet.attributes.getNamedItem("sheetId").nodeValue)
        assertEquals("rId1", sheet.attributes.getNamedItem("r:id").nodeValue)
    }

    @Test
    fun contentTypesCoversEveryPartOfThePackage() {
        val document = parseHardened("[Content_Types].xml", zipParts(templateBytes).getValue("[Content_Types].xml"))
        val defaults = document.getElementsByTagName("Default")
        val extensions =
            (0 until defaults.length).map { index ->
                val attributes = defaults.item(index).attributes
                attributes
                    .getNamedItem("Extension")
                    .nodeValue
            }
        assertTrue("rels" in extensions, "the rels default must cover _rels/.rels and xl/_rels/workbook.xml.rels")
        assertTrue("xml" in extensions, "the xml default must cover every remaining part")
    }

    @Test
    fun theSharedStringsPoolCarriesTheNineHeadersAndTheExampleStrings() {
        val document = parseHardened("xl/sharedStrings.xml", zipParts(templateBytes).getValue("xl/sharedStrings.xml"))
        val items = document.getElementsByTagName("si")
        val texts = (0 until items.length).map { item -> (items.item(item) as Element).textContent }
        // The 9 frozen headers are in the pool (the D-16 single constant source, byte-identical).
        SelfEntryTemplateGenerator.HEADERS.forEach { header ->
            assertTrue(header in texts, "the header $header must be a shared string")
        }
        // The D-16 example forms are all present (the three types, the 二级分类/账户2 demonstration
        // values and the text-form datetime).
        listOf("支出", "收入", "转账", "还信用卡", "现金", "银行卡", "2026-01-01 10:00:00").forEach { value ->
            assertTrue(value in texts, "the example value $value must be a shared string")
        }
        // count/uniqueCount agree with the pool (a strict editor cross-checks these).
        val sst = document.documentElement
        assertEquals(items.length.toString(), sst.attributes.getNamedItem("uniqueCount").nodeValue)
        assertEquals(items.length.toString(), sst.attributes.getNamedItem("count").nodeValue)
    }

    // ---------------------------------------------------------------- sheet coordinates + amount exactness

    @Test
    fun everyRowAndCellCarriesItsExplicitA1Reference() {
        val document = parseHardened("xl/worksheets/sheet1.xml", zipParts(templateBytes).getValue("xl/worksheets/sheet1.xml"))
        val rows = document.getElementsByTagName("row")
        assertEquals(4, rows.length, "header row + 3 example rows")
        (0 until rows.length).forEach { rowIndex ->
            val row = rows.item(rowIndex)
            assertEquals((rowIndex + 1).toString(), row.attributes.getNamedItem("r").nodeValue, "row ${rowIndex + 1} r")
            val cells = row.childNodes
            (0 until 9).forEach { columnIndex ->
                val cell = cells.item(columnIndex)
                val expected = columnLetters(columnIndex) + (rowIndex + 1)
                assertEquals(expected, cell.attributes.getNamedItem("r").nodeValue, "cell $expected r")
            }
            assertEquals(9, cells.length, "row ${rowIndex + 1} holds exactly the 9 contract columns")
        }
    }

    @Test
    fun theExampleAmountsAreLiteralDecimalTextsNeverFloats() {
        val sheet = zipParts(templateBytes).getValue("xl/worksheets/sheet1.xml").toString(Charsets.UTF_8)
        // AGENTS: money is exact — the sheet must carry the literal decimal strings the parser
        // reads back as the cached <v> text, never a binary-float serialization.
        assertTrue(sheet.contains(">12.34<"))
        assertTrue(sheet.contains(">56.78<"))
        assertTrue(sheet.contains(">90.12<"))
        assertTrue(
            !sheet.contains("E-") && !sheet.contains("e-") && !sheet.contains(".34000000000000"),
            "no scientific notation / float noise may enter an amount cell",
        )
        assertTrue(!sheet.contains("<f>"), "D-17: the template carries no formulas")
    }

    // ---------------------------------------------------------------- round trip (V-1, reader half)

    @Test
    fun theReaderResolvesOneSheetWithTheHeaderRowByteIdentical() {
        val workbook = BoundedXlsxReader.read(templateBytes)
        assertEquals(1, workbook.numberOfSheets)
        val header = workbook.firstSheet.getRow(0)!!
        SelfEntryTemplateGenerator.HEADERS.forEachIndexed { column, expected ->
            val cell = header.getCell(column)!!
            assertEquals(BoundedXlsxReader.CellType.STRING, cell.type)
            assertEquals(expected, cell.string, "header column ${columnLetters(column)}")
        }
        assertNull(header.getCell(9), "the frozen 9-column contract ends at column I")
    }

    @Test
    fun theReaderHandsBackTheThreeExampleRowsWithTheirD16Shapes() {
        val sheet = BoundedXlsxReader.read(templateBytes).firstSheet
        // 支出行: 账户2 与 二级分类 are the empty-string cells (不适用), amount = exact decimal text.
        val expense = sheet.getRow(1)!!
        assertEquals("支出", expense.getCell(0)!!.string)
        assertEquals("2026-01-01 10:00:00", expense.getCell(1)!!.string)
        assertEquals(BoundedXlsxReader.CellType.NUMERIC, expense.getCell(2)!!.type)
        assertEquals("12.34", expense.getCell(2)!!.rawValue)
        assertEquals("", expense.getCell(5)!!.string, "支出行 二级分类 留空")
        assertEquals("", expense.getCell(7)!!.string, "支出行 账户2 留空")
        // 收入行: same shape, amount exactness per row.
        val income = sheet.getRow(2)!!
        assertEquals("收入", income.getCell(0)!!.string)
        assertEquals("2026-01-02 11:30:00", income.getCell(1)!!.string)
        assertEquals("56.78", income.getCell(2)!!.rawValue)
        assertEquals("", income.getCell(7)!!.string, "收入行 账户2 留空")
        // 转账行: fills 账户2 and the 二级分类 (the D-16 forms the template demonstrates).
        val transfer = sheet.getRow(3)!!
        assertEquals("转账", transfer.getCell(0)!!.string)
        assertEquals("还信用卡", transfer.getCell(5)!!.string, "转账行 demonstrates 二级分类")
        assertEquals("现金", transfer.getCell(6)!!.string)
        assertEquals("银行卡", transfer.getCell(7)!!.string, "转账行 fills 账户2")
        assertEquals("90.12", transfer.getCell(2)!!.rawValue)
    }

    @Test
    fun aDoctypeBearingPartIsRejectedUnderTheHardeningConfig() {
        val poison = "<?xml version=\"1.0\"?><!DOCTYPE worksheet [<!ENTITY a \"b\">]><worksheet/>"
        // The hardened config rejects the DOCTYPE at parse time; the helper surfaces it as an
        // AssertionError (its wrapped SAXParseException), so the failure type is pinned here.
        assertFailsWith<AssertionError> { parseHardened("poison", poison.toByteArray(Charsets.UTF_8)) }
    }

    // ---------------------------------------------------------------- POI XSSF compatibility control (V-1 editor half)

    @Test
    fun poiXssfOpensThePackageAndReadsTheFrozenContract() {
        XSSFWorkbook(ByteArrayInputStream(templateBytes)).use { workbook ->
            assertEquals(SelfEntryTemplateGenerator.SHEET_NAME, workbook.getSheetName(0))
            val sheet = workbook.getSheetAt(0)
            SelfEntryTemplateGenerator.HEADERS.forEachIndexed { column, expected ->
                assertEquals(expected, sheet.getRow(0).getCell(column).stringCellValue)
            }
            assertEquals("支出", sheet.getRow(1).getCell(0).stringCellValue)
            assertEquals("转账", sheet.getRow(3).getCell(0).stringCellValue)
            assertEquals("银行卡", sheet.getRow(3).getCell(7).stringCellValue)
        }
    }

    // ---------------------------------------------------------------- constants (D-14/D-16 single source)

    @Test
    fun theNineHeadersAreTheFrozenD16ContractInColumnOrder() {
        assertEquals(
            listOf("类型", "日期时间", "金额", "币种", "一级分类", "二级分类", "账户1", "账户2", "备注"),
            SelfEntryTemplateGenerator.HEADERS,
        )
        assertEquals(9, SelfEntryTemplateGenerator.HEADERS.size)
    }

    @Test
    fun theFileNameCarriesTheTemplateVersion() {
        assertEquals("unifiedledger-import-template-v1.xlsx", SelfEntryTemplateGenerator.TEMPLATE_FILE_NAME)
        assertTrue(SelfEntryTemplateGenerator.TEMPLATE_FILE_NAME.endsWith("-v1.xlsx"))
    }

    // ---------------------------------------------------------------- helpers

    private fun zipPartNames(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
                zip.closeEntry()
            }
        }
        return names
    }

    private fun zipParts(bytes: ByteArray): Map<String, ByteArray> {
        val parts = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                parts[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return parts
    }

    /**
     * Namespace-aware DOM parse under the reader's hardening discipline: DOCTYPEs disallowed
     * (Xerces feature; the test fails loudly rather than degrading, since a generated template
     * must never carry one) and both external-entity features off.
     */
    private fun parseHardened(
        name: String,
        bytes: ByteArray,
    ): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        return try {
            factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        } catch (failure: SAXParseException) {
            throw AssertionError("part $name is not well-formed under the hardened config", failure)
        }
    }

    private fun columnLetters(columnIndex: Int): String {
        var remaining = columnIndex
        val letters = StringBuilder()
        while (remaining >= 0) {
            letters.append(('A' + remaining % 26))
            remaining = remaining / 26 - 1
        }
        return letters.reverse().toString()
    }
}
