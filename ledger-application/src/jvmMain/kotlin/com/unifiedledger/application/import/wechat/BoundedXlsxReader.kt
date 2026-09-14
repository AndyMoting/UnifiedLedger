package com.unifiedledger.application.import.wechat

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXNotRecognizedException
import org.xml.sax.SAXNotSupportedException
import org.xml.sax.ext.LexicalHandler
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.SAXParserFactory

/**
 * P7-04.A bounded minimal XLSX reader (D-146 Q08-3 / D-099 production-read revision, spec
 * section 3.1.3).
 *
 * Replaces POI XSSF on the WeChat production read path with the dual-platform JDK surface
 * only: `java.util.zip` for container streaming and `javax.xml.parsers` SAX for XML
 * streaming (both packages exist in AOSP libcore and the desktop JDK; `javax.xml.stream`
 * does not exist on Android, which is why the XSSF path cannot stay in production — spec
 * section 2.2 X-2/X-3/X-8). No new dependency; POI XSSF remains the jvmTest fixture
 * generator only.
 *
 * The reader covers the minimal semantic face the WeChat bill parser consumes and mirrors
 * the XSSF-observable behavior for exactly that face (equivalence criterion: the frozen
 * `WechatBillParserJvmTest` oracles pass unchanged over this reader):
 *
 * - package relationships: `_rels/.rels` -> workbook part, workbook rels -> first sheet and
 *   shared strings;
 * - `xl/sharedStrings.xml` shared strings (`<si>` items; rich-text runs concatenated);
 * - rows and columns by A1 `r` references (sequential-position fallback when absent);
 * - cell types `s` (shared string), `str` (formula string cache), `n`/absent (numeric with
 *   the exact cached `<v>` text — never routed through binary floating point), `b`
 *   (boolean), `e` (error), formula cells (`<f>` present -> FORMULA), inline strings
 *   (`<is><t>`); missing cells stay absent, never fabricated as empty values;
 * - a `<row>` element without `<c>` children stays a present row with
 *   [Row.lastCellNum] == -1; a sheet without rows reports [Sheet.lastRowNum] == -1
 *   (POI 5.5.1 `XSSFSheet.getLastRowNum`/`XSSFRow.getLastCellNum` semantics).
 *
 * Failures are typed by the caller along the existing D-097 container taxonomy
 * (`WechatBillParser.classifiedOpenFailure`): malformed containers/structures throw
 * [BoundedXlsxReadException] (-> INPUT_DECODE_FAILED), and an entry expanding beyond
 * [MAX_INFLATE_RATIO] times its consumed compressed size throws an IOException whose
 * message carries the frozen `Zip bomb detected!` marker (-> INPUT_UNSAFE_OR_OVER_LIMIT
 * with container scope), mirroring the untouched POI ZipSecureFile defaults.
 *
 * XML hardening is dual-line and portable (AB-BE-QUAL-01): (1) parser features — the two
 * standard SAX external-entity features are disabled on every in-scope implementation
 * (desktop Xerces and Android libcore alike; AOSP `ExpatReader` treats both as already-off
 * defaults), and the Apache Xerces-only `disallow-doctype-decl` feature is applied where
 * recognized, degrading gracefully where it is not (Android libcore's Harmony
 * `SAXParserFactoryImpl` rejects every non-`http://xml.org/sax/features/` name);
 * (2) [FailClosedHandler] — the handler layer rejects any DOCTYPE declaration and any
 * external entity resolution with [BoundedXlsxReadException] under *any* SAX
 * implementation, so the degraded path keeps the same fail-closed result. On Xerces the
 * DOCTYPE fatal error fires before any handler callback, so its existing raw
 * SAXParseException result is unchanged. `setXIncludeAware` is never called: the platform
 * default is already false, and the `javax.xml.parsers.SAXParserFactory` base method
 * throws `UnsupportedOperationException` on Android libcore (unoverridden there).
 *
 * Namespace-unawareness is part of the frozen minimal face (AB-BE-QUAL-07b): element
 * names are matched as written (qName). A document whose elements carry namespace
 * prefixes is outside the minimal face and does not parse (it surfaces through the
 * parser's frozen header/structure diagnostics, never as fabricated rows); WeChat
 * exports and the XSSF jvmTest fixtures are default-namespace documents, so the
 * observable face is unchanged.
 */
object BoundedXlsxReader {
    /** Mirrors the untouched POI ZipSecureFile default MIN_INFLATE_RATIO = 0.01 (100x expansion). */
    const val MAX_INFLATE_RATIO: Long = 100L

    private const val ROOT_RELS_NAME = "_rels/.rels"
    private const val OFFICE_DOCUMENT_RELATION_SUFFIX = "/officeDocument"
    private const val WORKSHEET_RELATION_SUFFIX = "/worksheet"
    private const val SHARED_STRINGS_RELATION_SUFFIX = "/sharedStrings"

    /** Apache Xerces-only DOCTYPE prohibition feature (not recognized on Android libcore). */
    private const val APACHE_DISALLOW_DOCTYPE_FEATURE = "http://apache.org/xml/features/disallow-doctype-decl"

    /** Standard SAX feature, recognized by desktop Xerces and Android libcore alike. */
    private const val SAX_EXTERNAL_GENERAL_ENTITIES_FEATURE = "http://xml.org/sax/features/external-general-entities"

    /** Standard SAX feature, recognized by desktop Xerces and Android libcore alike. */
    private const val SAX_EXTERNAL_PARAMETER_ENTITIES_FEATURE = "http://xml.org/sax/features/external-parameter-entities"

    /** Standard SAX property delivering LexicalHandler callbacks (Xerces and Android ExpatReader both support it). */
    private const val SAX_LEXICAL_HANDLER_PROPERTY = "http://xml.org/sax/properties/lexical-handler"

    /** Reader-level typed failure for malformed containers/structures (maps to INPUT_DECODE_FAILED). */
    class BoundedXlsxReadException(
        message: String,
    ) : RuntimeException(message)

    /** The POI-observable cell kinds consumed by the parser. */
    enum class CellType { STRING, NUMERIC, BOOLEAN, ERROR, FORMULA, BLANK }

    /** One worksheet cell. [rawValue] is the exact cached `<v>` text (never a parsed double). */
    class Cell(
        val type: CellType,
        val string: String?,
        val rawValue: String?,
    )

    /** One present `<row>` element (rows without cells stay present and empty). */
    class Row internal constructor(
        private val cells: Map<Int, Cell>,
    ) {
        /** XSSF `getLastCellNum` semantics: last present column + 1, or -1 when the row has no cell. */
        val lastCellNum: Int = if (cells.isEmpty()) -1 else (cells.keys.max() + 1)

        /** XSSF `getCell` semantics: absent cells stay absent. */
        fun getCell(column: Int): Cell? = cells[column]
    }

    /** One worksheet. */
    class Sheet internal constructor(
        private val rows: Map<Int, Row>,
    ) {
        /** XSSF `getLastRowNum` semantics: index of the last present `<row>`, or -1 when the sheet has none. */
        val lastRowNum: Int = if (rows.isEmpty()) -1 else rows.keys.max()

        /** XSSF `getRow` semantics: a `<row>` element without cells is still a present row. */
        fun getRow(index: Int): Row? = rows[index]
    }

    /** The minimal workbook face: the sheet count and the first sheet. */
    class Workbook internal constructor(
        val numberOfSheets: Int,
        val firstSheet: Sheet,
    )

    fun read(bytes: ByteArray): Workbook {
        val rootRels = parseRelsPart(bytes, ROOT_RELS_NAME)
        val workbookPart =
            rootRels
                .firstOrNull { it.type.endsWith(OFFICE_DOCUMENT_RELATION_SUFFIX) && !it.external }
                ?.target
                ?: throw BoundedXlsxReadException("package has no officeDocument relationship")
        val workbookPartName = resolveRelative("", workbookPart)
        val workbookDir = parentDirectory(workbookPartName)
        val workbookBytes = requirePart(bytes, workbookPartName)

        val workbook = parseWorkbookPart(workbookBytes)
        if (workbook.sheetCount == 0) {
            throw BoundedXlsxReadException("workbook declares no sheet")
        }
        val firstSheetRId =
            workbook.firstSheetRId
                ?: throw BoundedXlsxReadException("first sheet has no relationship id")
        val workbookRels = parseRelsPart(bytes, relsNameFor(workbookPartName))
        val firstSheetPart =
            workbookRels
                .firstOrNull { it.id == firstSheetRId && it.type.endsWith(WORKSHEET_RELATION_SUFFIX) && !it.external }
                ?.target
                ?: throw BoundedXlsxReadException("first sheet relationship $firstSheetRId is unresolvable")
        val sharedStringsPart =
            workbookRels
                .firstOrNull { it.type.endsWith(SHARED_STRINGS_RELATION_SUFFIX) && !it.external }
                ?.target

        val sharedStrings = sharedStringsPart?.let { parseSharedStrings(requirePart(bytes, resolveRelative(workbookDir, it))) }
        val sheetBytes = requirePart(bytes, resolveRelative(workbookDir, firstSheetPart))
        val sheet = parseSheetPart(sheetBytes, sharedStrings ?: emptyList())
        return Workbook(workbook.sheetCount, sheet)
    }

    // ---------------------------------------------------------------- container (java.util.zip)

    /** Counts raw bytes consumed from the underlying stream (the compressed side of the ratio guard). */
    private class CountingInputStream(
        source: InputStream,
    ) : FilterInputStream(source) {
        var count: Long = 0L
            private set

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) count++
            return value
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) count += read
            return read
        }
    }

    /** Fully reads one zip entry under the inflate-ratio guard (POI ZipSecureFile default mirror). */
    private fun readEntry(
        zip: ZipInputStream,
        raw: CountingInputStream,
        entry: ZipEntry,
    ): ByteArray {
        val entryStartRaw = raw.count
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var delivered = 0L
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            delivered += read
            val compressed = raw.count - entryStartRaw
            if (compressed > 0 && delivered > compressed * MAX_INFLATE_RATIO) {
                throw IOException(
                    "Zip bomb detected! entry '${entry.name}' expansion exceeds $MAX_INFLATE_RATIO x " +
                        "(uncompressed size: $delivered, raw/compressed size: $compressed)",
                )
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** Streams the package once and returns the bytes of [partName], or null when the part is absent. */
    private fun readPart(
        bytes: ByteArray,
        partName: String,
    ): ByteArray? {
        val raw = CountingInputStream(ByteArrayInputStream(bytes))
        ZipInputStream(raw).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == partName) {
                    return readEntry(zip, raw, entry)
                }
                zip.closeEntry()
            }
        }
        return null
    }

    private fun requirePart(
        bytes: ByteArray,
        partName: String,
    ): ByteArray =
        readPart(bytes, partName)
            ?: throw BoundedXlsxReadException("required package part is missing: $partName")

    // ---------------------------------------------------------------- package path resolution

    private data class Relationship(
        val id: String,
        val type: String,
        val target: String,
        val external: Boolean,
    )

    private fun parseRelsPart(
        bytes: ByteArray,
        relsName: String,
    ): List<Relationship> {
        val relsBytes = requirePart(bytes, relsName)
        val relationships = mutableListOf<Relationship>()
        parseXml(
            relsBytes,
            object : FailClosedHandler() {
                override fun startElement(
                    uri: String?,
                    localName: String?,
                    qName: String,
                    attributes: Attributes,
                ) {
                    if (qName == "Relationship") {
                        relationships +=
                            Relationship(
                                id = attributes.getValue("Id") ?: "",
                                type = attributes.getValue("Type") ?: "",
                                target = attributes.getValue("Target") ?: "",
                                external = attributes.getValue("TargetMode") == "External",
                            )
                    }
                }
            },
        )
        return relationships
    }

    /** `_rels/<base>.rels` for a part `dir/base` (OPC convention). */
    private fun relsNameFor(partName: String): String {
        val directory = parentDirectory(partName)
        val base = partName.substringAfterLast('/')
        return if (directory.isEmpty()) "_rels/$base.rels" else "$directory/_rels/$base.rels"
    }

    private fun parentDirectory(partName: String): String = partName.substringBeforeLast('/', "")

    /**
     * Resolves a (possibly relative, possibly part-absolute) relationship target against the
     * base directory, with `.`/`..` segment handling. Root rels have an empty base directory.
     */
    private fun resolveRelative(
        baseDirectory: String,
        target: String,
    ): String {
        if (target.startsWith("/")) return normalizeSegments(target.removePrefix("/"))
        return normalizeSegments(if (baseDirectory.isEmpty()) target else "$baseDirectory/$target")
    }

    private fun normalizeSegments(path: String): String {
        val segments = mutableListOf<String>()
        path.split('/').forEach { segment ->
            when (segment) {
                "", "." -> {}
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                else -> segments.add(segment)
            }
        }
        return segments.joinToString("/")
    }

    // ---------------------------------------------------------------- workbook / shared strings

    private class WorkbookHeader(
        val sheetCount: Int,
        val firstSheetRId: String?,
    )

    private fun parseWorkbookPart(bytes: ByteArray): WorkbookHeader {
        var count = 0
        var firstRId: String? = null
        parseXml(
            bytes,
            object : FailClosedHandler() {
                override fun startElement(
                    uri: String?,
                    localName: String?,
                    qName: String,
                    attributes: Attributes,
                ) {
                    if (qName == "sheet") {
                        if (count == 0) firstRId = attributes.getValue("r:id")
                        count++
                    }
                }
            },
        )
        return WorkbookHeader(count, firstRId)
    }

    /** Shared strings: each `<si>` item is the concatenation of all its `<t>` runs (XSSF semantics). */
    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val strings = mutableListOf<String>()
        parseXml(
            bytes,
            object : FailClosedHandler() {
                private var inItem = false
                private var inText = false
                private val text = StringBuilder()

                override fun startElement(
                    uri: String?,
                    localName: String?,
                    qName: String,
                    attributes: Attributes,
                ) {
                    when (qName) {
                        "si" -> {
                            inItem = true
                            text.setLength(0)
                        }
                        "t" -> inText = inItem
                    }
                }

                override fun characters(
                    ch: CharArray,
                    start: Int,
                    length: Int,
                ) {
                    if (inItem && inText) text.append(ch, start, length)
                }

                override fun endElement(
                    uri: String?,
                    localName: String?,
                    qName: String,
                ) {
                    when (qName) {
                        "t" -> inText = false
                        "si" -> {
                            strings.add(text.toString())
                            inItem = false
                        }
                    }
                }
            },
        )
        return strings
    }

    // ---------------------------------------------------------------- worksheet

    private fun parseSheetPart(
        bytes: ByteArray,
        sharedStrings: List<String>,
    ): Sheet {
        val rows = HashMap<Int, Row>()
        parseXml(
            bytes,
            object : FailClosedHandler() {
                private var inSheetData = false
                private var rowsSeen = 0

                private var currentRowIndex = -1
                private var currentCells: LinkedHashMap<Int, Cell>? = null

                private var currentCellColumn = -1
                private var currentCellType: String? = null
                private var currentCellHasFormula = false
                private var currentCellIsInline = false
                private var valueText: StringBuilder? = null
                private var inlineText: StringBuilder? = null
                private var collectingValue = false
                private var collectingInlineText = false

                override fun startElement(
                    uri: String?,
                    localName: String?,
                    qName: String,
                    attributes: Attributes,
                ) {
                    when (qName) {
                        "sheetData" -> inSheetData = true
                        "row" -> if (inSheetData) startRow(attributes)
                        "c" -> if (inSheetData && currentCells != null) startCell(attributes)
                        "f" -> if (inSheetData && currentCells != null) currentCellHasFormula = true
                        "v" ->
                            if (inSheetData && currentCells != null) {
                                collectingValue = true
                                valueText = StringBuilder()
                            }
                        "is" -> if (inSheetData && currentCells != null) currentCellIsInline = true
                        "t" ->
                            if (inSheetData && currentCells != null && currentCellIsInline) {
                                collectingInlineText = true
                                if (inlineText == null) inlineText = StringBuilder()
                            }
                    }
                }

                override fun characters(
                    ch: CharArray,
                    start: Int,
                    length: Int,
                ) {
                    if (collectingValue) valueText?.append(ch, start, length)
                    if (collectingInlineText) inlineText?.append(ch, start, length)
                }

                override fun endElement(
                    uri: String?,
                    localName: String?,
                    qName: String,
                ) {
                    when (qName) {
                        "v" -> collectingValue = false
                        "t" -> collectingInlineText = false
                        "c" -> if (inSheetData && currentCells != null) finishCell()
                        "row" -> if (inSheetData) finishRow()
                        "sheetData" -> inSheetData = false
                    }
                }

                private fun startRow(attributes: Attributes) {
                    val reference = attributes.getValue("r")
                    currentRowIndex =
                        if (reference == null) {
                            rowsSeen
                        } else {
                            val oneBased =
                                reference.toIntOrNull()
                                    ?: throw BoundedXlsxReadException("row reference is not numeric: $reference")
                            if (oneBased < 1) throw BoundedXlsxReadException("row reference is not 1-based: $reference")
                            oneBased - 1
                        }
                    rowsSeen = currentRowIndex + 1
                    currentCells = LinkedHashMap()
                    currentCellColumn = -1
                }

                private fun startCell(attributes: Attributes) {
                    val reference = attributes.getValue("r")
                    currentCellColumn =
                        if (reference == null) {
                            currentCellColumn + 1
                        } else {
                            columnIndexOf(reference)
                        }
                    currentCellType = attributes.getValue("t")
                    currentCellHasFormula = false
                    currentCellIsInline = false
                    valueText = null
                    inlineText = null
                    collectingValue = false
                    collectingInlineText = false
                }

                private fun finishCell() {
                    val column = currentCellColumn
                    if (column < 0) throw BoundedXlsxReadException("cell without a resolvable column")
                    val rawValue = valueText?.toString()
                    val cell =
                        when {
                            currentCellHasFormula -> Cell(CellType.FORMULA, null, rawValue)
                            currentCellType == "s" -> {
                                val index =
                                    rawValue?.trim()?.toIntOrNull()
                                        ?: throw BoundedXlsxReadException("shared string cell has no integer value")
                                val text =
                                    sharedStrings.getOrNull(index)
                                        ?: throw BoundedXlsxReadException("shared string index out of range: $index")
                                Cell(CellType.STRING, text, rawValue)
                            }
                            currentCellType == "str" -> Cell(CellType.STRING, rawValue, rawValue)
                            currentCellType == "inlineStr" -> {
                                val text = inlineText?.toString() ?: ""
                                Cell(CellType.STRING, text, text)
                            }
                            currentCellType == "b" -> Cell(CellType.BOOLEAN, null, rawValue)
                            currentCellType == "e" -> Cell(CellType.ERROR, null, rawValue)
                            currentCellType == null || currentCellType == "n" ->
                                if (rawValue == null) {
                                    Cell(CellType.BLANK, null, null)
                                } else {
                                    Cell(CellType.NUMERIC, null, rawValue)
                                }
                            else -> throw BoundedXlsxReadException("unsupported cell type: $currentCellType")
                        }
                    currentCells?.put(column, cell)
                }

                private fun finishRow() {
                    if (currentRowIndex < 0) throw BoundedXlsxReadException("row element outside sheetData")
                    rows[currentRowIndex] = Row(currentCells ?: emptyMap())
                    currentRowIndex = -1
                    currentCells = null
                    currentCellColumn = -1
                }
            },
        )
        return Sheet(rows)
    }

    /** Parses an A1 reference into its 0-based column index (`"AB12"` -> 27). */
    private fun columnIndexOf(reference: String): Int {
        var letters = 0
        var oneBased = 0
        while (letters < reference.length && !reference[letters].isDigit()) {
            val character = reference[letters]
            if (character !in 'A'..'Z' && character !in 'a'..'z') {
                throw BoundedXlsxReadException("cell reference is not A1-style: $reference")
            }
            oneBased = oneBased * 26 + (character.uppercaseChar() - 'A' + 1)
            letters++
        }
        if (letters == 0 || letters == reference.length) {
            throw BoundedXlsxReadException("cell reference is not A1-style: $reference")
        }
        return oneBased - 1
    }

    // ---------------------------------------------------------------- SAX plumbing

    /**
     * Fail-closed handler base (AB-BE-QUAL-01 portable hardening, second line): any DOCTYPE
     * declaration or external entity resolution is rejected with the reader's typed failure
     * under *any* SAX implementation, so a factory that does not recognize the Apache
     * `disallow-doctype-decl` feature (Android libcore) keeps the same fail-closed result as
     * the Xerces path. Xerces never reaches these callbacks for DTD-bearing input (its
     * DOCTYPE fatal error fires first), so its existing raw SAXParseException result is
     * unchanged. Messages stay static (no input-derived names/URIs; D06/D-097 discipline).
     */
    internal abstract class FailClosedHandler :
        DefaultHandler(),
        LexicalHandler {
        /** Any DTD in an xlsx part is rejected: fail-closed on the DOCTYPE start itself. */
        final override fun startDTD(
            name: String?,
            publicId: String?,
            systemId: String?,
        ): Unit = throw BoundedXlsxReadException("doctype declarations are not allowed in xlsx parts")

        /** Belt-and-braces: even a parser that never reported the DTD start cannot resolve. */
        final override fun resolveEntity(
            publicId: String?,
            systemId: String?,
        ): InputSource? = throw BoundedXlsxReadException("external entity resolution is not allowed in xlsx parts")

        // LexicalHandler reporting callbacks: no-op (DTD-bearing input never reaches them).
        final override fun endDTD() {}

        final override fun startCDATA() {}

        final override fun endCDATA() {}

        final override fun startEntity(name: String?) {}

        final override fun endEntity(name: String?) {}

        final override fun comment(
            ch: CharArray,
            start: Int,
            length: Int,
        ) {}
    }

    /**
     * Hardens one [SAXParserFactory] for the dual-platform production read (AB-BE-QUAL-01).
     *
     * Only names every in-scope SAX implementation accepts are set unguarded: desktop Xerces
     * and Android libcore both take the two standard `http://xml.org/sax/features/` entity
     * features (libcore's Harmony `SAXParserFactoryImpl` rejects every non-prefixed name
     * with SAXNotRecognizedException and routes the prefixed ones to ExpatReader, which
     * accepts both entity features as already-off defaults). The Apache Xerces-only
     * `disallow-doctype-decl` feature is attempted first and its non-support degrades
     * gracefully to the [FailClosedHandler] layer. `setXIncludeAware` is intentionally
     * never called: the platform default is already false, and the
     * `javax.xml.parsers.SAXParserFactory` base method throws
     * `UnsupportedOperationException` on Android libcore (unoverridden there).
     */
    internal fun hardenSaxFactory(factory: SAXParserFactory): SAXParserFactory {
        factory.isNamespaceAware = false
        // Container hardening: no DTDs and no external entities ever load through this
        // reader (fail-closed on attempt).
        try {
            factory.setFeature(APACHE_DISALLOW_DOCTYPE_FEATURE, true)
        } catch (failure: SAXNotRecognizedException) {
            // Degraded path (e.g. Android libcore): FailClosedHandler stays fail-closed.
        } catch (failure: SAXNotSupportedException) {
            // Degraded path: FailClosedHandler stays fail-closed.
        } catch (failure: ParserConfigurationException) {
            // Degraded path: FailClosedHandler stays fail-closed.
        }
        factory.setFeature(SAX_EXTERNAL_GENERAL_ENTITIES_FEATURE, false)
        factory.setFeature(SAX_EXTERNAL_PARAMETER_ENTITIES_FEATURE, false)
        return factory
    }

    private fun parseXml(
        bytes: ByteArray,
        handler: FailClosedHandler,
    ) = parseXmlWith(hardenSaxFactory(SAXParserFactory.newInstance()), bytes, handler)

    /**
     * Parses one XML part through an already-configured [factory]. The lexical-handler
     * property is registered explicitly — JAXP `parse` wires only the four core handler
     * interfaces — and both in-scope SAX implementations support it. A SAX implementation
     * that ever could not register lexical callbacks fails loudly here (the read maps to
     * INPUT_DECODE_FAILED) instead of silently losing the fail-closed DTD defense.
     */
    internal fun parseXmlWith(
        factory: SAXParserFactory,
        bytes: ByteArray,
        handler: FailClosedHandler,
    ) {
        val parser = factory.newSAXParser()
        parser.setProperty(SAX_LEXICAL_HANDLER_PROPERTY, handler)
        parser.parse(ByteArrayInputStream(bytes), handler)
    }
}
