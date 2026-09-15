package com.unifiedledger.application.import.wechat

import org.xml.sax.SAXException
import org.xml.sax.SAXNotRecognizedException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.parsers.SAXParser
import javax.xml.parsers.SAXParserFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P7-04.A bounded minimal XLSX reader unit tests (D-146 Q08-3, spec section 3.1.3).
 *
 * Every package is hand-crafted here (zip + OOXML parts), so the reader-level semantics are
 * pinned independently of POI: `should be identical to the XSSF-observable face the WeChat
 * parser consumes`. All data is anonymous synthetic content. The frozen oracle equivalence
 * proof stays `WechatBillParserJvmTest` (unmodified expectations over the new reader); these
 * tests cover the parts of the reader face that the parser never exercises but the reader
 * contract still declares.
 */
class BoundedXlsxReaderJvmTest {
    // ---------------------------------------------------------------- synthetic package builder

    private class PackageBuilder {
        private val parts = LinkedHashMap<String, ByteArray>()
        var firstSheetTarget: String = "worksheets/sheet1.xml"

        fun part(
            name: String,
            content: String,
        ): PackageBuilder {
            parts[name] = content.toByteArray(Charsets.UTF_8)
            return this
        }

        fun rels(
            name: String,
            vararg relationships: String,
        ): PackageBuilder {
            val body =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    relationships.joinToString("") { it } +
                    "</Relationships>"
            return part(name, body)
        }

        fun bytes(): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                parts.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content)
                    zip.closeEntry()
                }
            }
            return out.toByteArray()
        }
    }

    private fun officeDocumentRel() = "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"

    private fun workbook(sheetCount: Int = 1): String =
        buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>")
            repeat(sheetCount) { index ->
                append("<sheet name=\"S$index\" sheetId=\"${index + 1}\" r:id=\"rId${index + 10}\"/>")
            }
            append("</sheets></workbook>")
        }

    private fun workbookRels(
        sharedStrings: Boolean,
        sheetCount: Int = 1,
    ): String {
        val rels = mutableListOf<String>()
        repeat(sheetCount) { index ->
            rels +=
                "<Relationship Id=\"rId${index + 10}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${index + 1}.xml\"/>"
        }
        if (sharedStrings) {
            rels +=
                "<Relationship Id=\"rId99\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/>"
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            rels.joinToString("") +
            "</Relationships>"
    }

    private fun basicPackage(
        sheet: String,
        sharedStrings: String? = null,
        sheetCount: Int = 1,
    ): ByteArray =
        PackageBuilder()
            .rels("_rels/.rels", officeDocumentRel())
            .part("xl/workbook.xml", workbook(sheetCount))
            .part("xl/_rels/workbook.xml.rels", workbookRels(sharedStrings != null, sheetCount))
            .apply { sharedStrings?.let { part("xl/sharedStrings.xml", it) } }
            .part("xl/worksheets/sheet1.xml", sheet)
            .bytes()

    private fun sheetData(rows: String): String = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>$rows</sheetData></worksheet>"

    private val emptySharedStrings: String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"0\" uniqueCount=\"0\"/>"

    // ---------------------------------------------------------------- shared strings

    @Test
    fun sharedStringsResolveForSCellsAndRichRunsConcatenate() {
        val sharedStrings =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
                "<si><t>SYN-ALPHA</t></si>" +
                "<si><r><t>SYN-BETA-</t></r><r><t>RUN</t></r></si>" +
                "</sst>"
        val bytes =
            basicPackage(
                sheetData("<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row>"),
                sharedStrings = sharedStrings,
            )
        val sheet = BoundedXlsxReader.read(bytes).firstSheet
        val row = assertNotNullRow(sheet.getRow(0))
        assertEquals(2, row.lastCellNum)
        val first = assertIs<BoundedXlsxReader.Cell>(row.getCell(0))
        assertEquals(BoundedXlsxReader.CellType.STRING, first.type)
        assertEquals("SYN-ALPHA", first.string)
        assertEquals("SYN-BETA-RUN", assertIs<BoundedXlsxReader.Cell>(row.getCell(1)).string)
    }

    @Test
    fun sharedStringIndexOutOfRangeIsTypedStructuralFailure() {
        val bytes =
            basicPackage(
                sheetData("<row r=\"1\"><c r=\"A1\" t=\"s\"><v>7</v></c></row>"),
                sharedStrings = emptySharedStrings,
            )
        val failure = assertFailsWith<BoundedXlsxReader.BoundedXlsxReadException> { BoundedXlsxReader.read(bytes) }
        assertTrue(failure.message.orEmpty().contains("shared string index out of range"))
    }

    // ---------------------------------------------------------------- inline string / numeric / missing

    @Test
    fun inlineStringsAndNumericTextAndMissingCells() {
        val sheet =
            sheetData(
                "<row r=\"1\">" +
                    "<c r=\"A1\" t=\"inlineStr\"><is><t>SYN-INLINE</t></is></c>" +
                    "<c r=\"B1\"><v>128.50</v></c>" +
                    "<c r=\"D1\"><v>0.00</v></c>" +
                    "</row>",
            )
        val row = assertNotNullRow(BoundedXlsxReader.read(basicPackage(sheet)).firstSheet.getRow(0))
        // Missing C1 stays absent; D1 widens the row: lastCellNum = 4 (0-based D + 1).
        assertEquals(4, row.lastCellNum)
        assertEquals("SYN-INLINE", assertIs<BoundedXlsxReader.Cell>(row.getCell(0)).string)
        val numeric = assertIs<BoundedXlsxReader.Cell>(row.getCell(1))
        assertEquals(BoundedXlsxReader.CellType.NUMERIC, numeric.type)
        // Exact cached decimal text; never routed through binary floating point.
        assertEquals("128.50", numeric.rawValue)
        assertEquals("0.00", assertIs<BoundedXlsxReader.Cell>(row.getCell(3)).rawValue)
        assertNull(row.getCell(2))
    }

    @Test
    fun formulaStringCacheAndValueLessCellKeepTheirDeclaredTypes() {
        val sheet = sheetData("<row r=\"1\"><c r=\"A1\" t=\"str\"><v>SYN-CACHED</v></c><c r=\"B1\"/></row>")
        val row = assertNotNullRow(BoundedXlsxReader.read(basicPackage(sheet)).firstSheet.getRow(0))
        assertEquals(2, row.lastCellNum)
        val formulaCache = assertIs<BoundedXlsxReader.Cell>(row.getCell(0))
        assertEquals(BoundedXlsxReader.CellType.STRING, formulaCache.type)
        assertEquals("SYN-CACHED", formulaCache.string)
        // A `<c>` element with neither `t` nor `<v>` is a present blank cell, never fabricated.
        val valueLess = assertIs<BoundedXlsxReader.Cell>(row.getCell(1))
        assertEquals(BoundedXlsxReader.CellType.BLANK, valueLess.type)
        assertNull(valueLess.string)
    }

    @Test
    fun formulaBooleanAndErrorCellsCarryTheirCellType() {
        val sheet =
            sheetData(
                "<row r=\"1\">" +
                    "<c r=\"A1\"><f>SUM(B1)</f><v>42</v></c>" +
                    "<c r=\"B1\" t=\"b\"><v>1</v></c>" +
                    "<c r=\"C1\" t=\"e\"><v>#DIV/0!</v></c>" +
                    "</row>",
            )
        val row = assertNotNullRow(BoundedXlsxReader.read(basicPackage(sheet)).firstSheet.getRow(0))
        assertEquals(BoundedXlsxReader.CellType.FORMULA, assertIs<BoundedXlsxReader.Cell>(row.getCell(0)).type)
        assertEquals(BoundedXlsxReader.CellType.BOOLEAN, assertIs<BoundedXlsxReader.Cell>(row.getCell(1)).type)
        assertEquals(BoundedXlsxReader.CellType.ERROR, assertIs<BoundedXlsxReader.Cell>(row.getCell(2)).type)
    }

    // ---------------------------------------------------------------- row / sheet shape

    @Test
    fun rowWithoutCellsStaysPresentAndEmptySheetReportsMinusOne() {
        val present = BoundedXlsxReader.read(basicPackage(sheetData("<row r=\"2\"/>"))).firstSheet
        val row = assertNotNullRow(present.getRow(1))
        assertEquals(-1, row.lastCellNum)
        assertNull(row.getCell(0))
        assertEquals(1, present.lastRowNum)

        val empty = BoundedXlsxReader.read(basicPackage(sheetData(""))).firstSheet
        assertEquals(-1, empty.lastRowNum)
        assertNull(empty.getRow(0))
    }

    @Test
    fun multipleSheetsReportTheFirstSheetCount() {
        val packageBytes =
            PackageBuilder()
                .rels("_rels/.rels", officeDocumentRel())
                .part("xl/workbook.xml", workbook(sheetCount = 2))
                .part("xl/_rels/workbook.xml.rels", workbookRels(false, sheetCount = 2))
                .part("xl/worksheets/sheet1.xml", sheetData("<row r=\"1\"><c r=\"A1\"><v>1</v></c></row>"))
                .part("xl/worksheets/sheet2.xml", sheetData("<row r=\"1\"><c r=\"A1\"><v>2</v></c></row>"))
                .bytes()
        val workbook = BoundedXlsxReader.read(packageBytes)
        assertEquals(2, workbook.numberOfSheets)
        assertEquals("1", assertIs<BoundedXlsxReader.Cell>(assertNotNullRow(workbook.firstSheet.getRow(0)).getCell(0)).rawValue)
    }

    @Test
    fun rowsAndCellsLackingReferencesFallBackToSequentialPositions() {
        // AB-BE-QUAL-07c: `<row>`/`<c>` elements without the A1 `r` attribute take sequential
        // positions (rows follow rowsSeen, cells follow the previous column) — direct pin of
        // the reader's position fallback.
        val sheet =
            sheetData(
                "<row>" +
                    "<c t=\"inlineStr\"><is><t>SYN-POS-A</t></is></c>" +
                    "<c><v>17.25</v></c>" +
                    "</row>" +
                    "<row><c t=\"inlineStr\"><is><t>SYN-POS-B</t></is></c></row>",
            )
        val parsedSheet = BoundedXlsxReader.read(basicPackage(sheet)).firstSheet
        // Rows land on sequential 0-based indexes 0 and 1.
        assertEquals(1, parsedSheet.lastRowNum)
        val first = assertNotNullRow(parsedSheet.getRow(0))
        assertEquals("SYN-POS-A", assertIs<BoundedXlsxReader.Cell>(first.getCell(0)).string)
        assertEquals("17.25", assertIs<BoundedXlsxReader.Cell>(first.getCell(1)).rawValue)
        assertEquals(2, first.lastCellNum)
        val second = assertNotNullRow(parsedSheet.getRow(1))
        assertEquals("SYN-POS-B", assertIs<BoundedXlsxReader.Cell>(second.getCell(0)).string)
        assertEquals(1, second.lastCellNum)
    }

    // ---------------------------------------------------------------- container / structural failures

    @Test
    fun malformedContainersAreTypedFailures() {
        assertFailsWith<BoundedXlsxReader.BoundedXlsxReadException> { BoundedXlsxReader.read(ByteArray(0)) }
        assertFailsWith<BoundedXlsxReader.BoundedXlsxReadException> {
            BoundedXlsxReader.read("not a zip at all".toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun missingRequiredPartsAreTypedFailures() {
        // No officeDocument relationship at all.
        val noRootRel = PackageBuilder().rels("_rels/.rels").part("xl/workbook.xml", workbook()).bytes()
        assertFailsWith<BoundedXlsxReader.BoundedXlsxReadException> { BoundedXlsxReader.read(noRootRel) }

        // Workbook part present but the sheet part referenced by the rels is missing.
        val missingSheet =
            PackageBuilder()
                .rels("_rels/.rels", officeDocumentRel())
                .part("xl/workbook.xml", workbook())
                .part("xl/_rels/workbook.xml.rels", workbookRels(false))
                .bytes()
        assertFailsWith<BoundedXlsxReader.BoundedXlsxReadException> { BoundedXlsxReader.read(missingSheet) }
    }

    // ---------------------------------------------------------------- inflate-ratio guard (frozen POI default)

    @Test
    fun zipBombExpansionIsTypedThroughTheFrozenIoMarker() {
        // 100x expansion cap mirror: a requested part expanding well past the ratio triggers
        // the frozen `Zip bomb detected!` IOException marker (POI ZipSecureFile default
        // mirror). The guard runs inside readEntry before any XML parsing.
        val sheetPart = ByteArray(2 * 1024 * 1024) { 'A'.code.toByte() }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(officeDocumentRelBytes())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/workbook.xml"))
            zip.write(workbook().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
            zip.write(workbookRels(false).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write(sheetPart)
            zip.closeEntry()
        }
        val failure = assertFailsWith<IOException> { BoundedXlsxReader.read(out.toByteArray()) }
        assertTrue(failure.message.orEmpty().contains("Zip bomb detected!", ignoreCase = true))
    }

    // ------------------------------------------------- DOCTYPE / external-entity hardening (AB-BE-QUAL-01)

    /**
     * A container-valid package carrying the given sheet part: the WeChat container
     * pre-check passes ([Content_Types].xml and xl/workbook.xml present), so any rejection
     * comes from the reader's XML layer alone.
     */
    private fun packageWithSheetPart(sheetPart: String): ByteArray =
        PackageBuilder()
            .part("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>")
            .rels("_rels/.rels", officeDocumentRel())
            .part("xl/workbook.xml", workbook())
            .part("xl/_rels/workbook.xml.rels", workbookRels(false))
            .part("xl/worksheets/sheet1.xml", sheetPart)
            .bytes()

    private val doctypeInjectedSheet: String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<!DOCTYPE worksheet [<!ENTITY e \"x\">]>" +
            "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>" +
            "<row r=\"1\"><c r=\"A1\"><v>1</v></c></row>" +
            "</sheetData></worksheet>"

    @Test
    fun doctypeInjectionIsRejectedWithTheCurrentTypedResultAndFrozenDiagnostic() {
        val bytes = packageWithSheetPart(doctypeInjectedSheet)
        // Reader level: the current typed result on the Xerces path is the raw SAX family
        // failure raised by the disallow-doctype-decl fatal error (no handler callback fires
        // before it) — unchanged by the hardening rework.
        assertFailsWith<SAXException> { BoundedXlsxReader.read(bytes) }
        // Parser level: the frozen diagnostic result is INPUT_DECODE_FAILED.
        val rejected = WechatBillParser.parse("syn-ref-doctype", bytes)
        assertEquals(WechatBatchOutcome.REJECTED, rejected.outcome)
        assertEquals("INPUT_DECODE_FAILED", rejected.diagnostic?.code)
    }

    @Test
    fun degradedFactoryWithoutApacheFeatureKeepsTheSameFailClosedResult() {
        // Android-shaped SAXParserFactory stand-in: rejects every non-
        // `http://xml.org/sax/features/` name like libcore's Harmony SAXParserFactoryImpl,
        // records prefixed ones and applies them to a real delegate parser at newSAXParser
        // time (mirroring libcore's features map), and throws from setXIncludeAware like
        // the unoverridden libcore base.
        val androidLike =
            object : SAXParserFactory() {
                val appliedFeatures = mutableListOf<Pair<String, Boolean>>()
                var xIncludeAwareProbes = 0

                override fun setFeature(
                    name: String,
                    value: Boolean,
                ) {
                    if (!name.startsWith("http://xml.org/sax/features/")) {
                        throw SAXNotRecognizedException(name)
                    }
                    appliedFeatures += name to value
                }

                override fun getFeature(name: String): Boolean = appliedFeatures.firstOrNull { it.first == name }?.second ?: false

                override fun newSAXParser(): SAXParser {
                    val delegate = SAXParserFactory.newInstance()
                    delegate.isNamespaceAware = isNamespaceAware
                    appliedFeatures.forEach { (name, value) -> delegate.setFeature(name, value) }
                    return delegate.newSAXParser()
                }

                override fun setXIncludeAware(state: Boolean) {
                    xIncludeAwareProbes++
                    throw UnsupportedOperationException("probe: setXIncludeAware is not overridden on Android libcore")
                }
            }

        // The hardening must not throw on the degraded factory, must not touch
        // setXIncludeAware, and must still set the two portable standard features.
        BoundedXlsxReader.hardenSaxFactory(androidLike)
        assertEquals(0, androidLike.xIncludeAwareProbes)
        assertEquals(
            listOf(
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
            ),
            androidLike.appliedFeatures,
        )

        // The handler layer stays fail-closed through the degraded configuration: the same
        // DOCTYPE injection is rejected with the reader's typed failure.
        val handler = object : BoundedXlsxReader.FailClosedHandler() {}
        val failure =
            assertFailsWith<BoundedXlsxReader.BoundedXlsxReadException> {
                BoundedXlsxReader.parseXmlWith(androidLike, doctypeInjectedSheet.toByteArray(Charsets.UTF_8), handler)
            }
        assertTrue(failure.message.orEmpty().contains("doctype declarations are not allowed"))

        // And the degraded configuration does not false-reject ordinary DTD-free parts.
        BoundedXlsxReader.parseXmlWith(
            androidLike,
            sheetData("<row r=\"1\"><c r=\"A1\"><v>1</v></c></row>").toByteArray(Charsets.UTF_8),
            handler,
        )
    }

    @Test
    fun readerTypedStructuralFailureMapsToTheSameFrozenDiagnostic() {
        // Equivalence chain closure: the degraded DOCTYPE path rejects with exactly this
        // typed failure, and the parser maps that failure family to the same
        // INPUT_DECODE_FAILED diagnostic the Xerces DOCTYPE path produces
        // (see doctypeInjectionIsRejectedWithTheCurrentTypedResultAndFrozenDiagnostic).
        val missingSheet =
            PackageBuilder()
                .part("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>")
                .rels("_rels/.rels", officeDocumentRel())
                .part("xl/workbook.xml", workbook())
                .part("xl/_rels/workbook.xml.rels", workbookRels(false))
                .bytes()
        assertFailsWith<BoundedXlsxReader.BoundedXlsxReadException> { BoundedXlsxReader.read(missingSheet) }
        val rejected = WechatBillParser.parse("syn-ref-missing-part", missingSheet)
        assertEquals(WechatBatchOutcome.REJECTED, rejected.outcome)
        assertEquals("INPUT_DECODE_FAILED", rejected.diagnostic?.code)
    }

    private fun officeDocumentRelBytes(): ByteArray {
        val body =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                officeDocumentRel() +
                "</Relationships>"
        return body.toByteArray(Charsets.UTF_8)
    }

    private fun assertNotNullRow(row: BoundedXlsxReader.Row?): BoundedXlsxReader.Row {
        requireNotNull(row) { "expected a present row" }
        return row
    }
}
