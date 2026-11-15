package com.unifiedledger.application.export.readable

import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * P8-05 批 a (D-331 spec D-E3): the JVM streaming adapter that turns the self-owned
 * [ReadableExportSink] byte face into the six-part minimal OOXML workbook, one row at a time.
 * Shape changed from `SelfEntryTemplateGenerator` exactly per the spec's two deltas:
 * `xl/worksheets/sheet1.xml` is written row by row straight through the zip (no
 * `ByteArrayOutputStream`, no whole-sheet string) and every text cell is an inline string
 * (`t="inlineStr"><is><t>` — inside `BoundedXlsxReader`'s semantic face, `BoundedXlsxReader.kt:41`
 * and the `inlineStr` arm at `:527-530`), dropping the shared-strings pool (R1: a 50k-row pool
 * would hold ~10-15 MB alive until the last row). Amounts are numeric cells whose `<v>` is the
 * EXACT decimal text — never a binary float (the template precedent,
 * `SelfEntryTemplateGenerator.kt:232-243`). Zip entries are frozen at the DOS epoch with
 * `BEST_SPEED` (`:308-319` family, reproducible bytes); the part list is EXACTLY the template's
 * six parts — no docProps, no styles (D-E7 zero-metadata declaration, asserted by the structure
 * test).
 *
 * Lifecycle: the first write lazily [open]s the package (four static parts + `sheet1.xml`),
 * [writeHeader] the frozen ten-column row, [writeRow] one data row each, [commit] closes
 * `sheetData`/worksheet, finishes the zip and commits the target exactly once, [close] abandons
 * an uncommitted target (idempotent; the sink itself is closed once, here — the bridge layer
 * hands it over).
 */
class ReadableLedgerXlsxStreamWriter(
    private val sink: ReadableExportSink,
) : ReadableExportSheetWriter {
    private val zip = ZipOutputStream(SinkOutputStream(sink))
    private var sheetOpen = false
    private var closed = false
    private var staticPartCursor = 0
    private var nextRowIndex = 1

    /** Data rows streamed so far (the V-3 consumed-page observable for the fake page source). */
    var rowsWritten: Int = 0
        private set

    /** Writes the four static parts and opens `sheet1.xml` with the `<sheetData>` prologue. Idempotent. */
    fun open() {
        if (sheetOpen || closed) return
        zip.setLevel(Deflater.BEST_SPEED)
        writeStaticPart(CONTENT_TYPES_XML)
        writeStaticPart(ROOT_RELS_XML)
        writeStaticPart(WORKBOOK_XML)
        writeStaticPart(WORKBOOK_RELS_XML)
        // The shared-strings part is intentionally ABSENT from the workbook rels: D-E3 drops the
        // pool and the reader resolves the part optionally (BoundedXlsxReader.kt:162-167).
        val entry = ZipEntry(SHEET_PART)
        entry.time = 0L
        zip.putNextEntry(entry)
        zip.write(SHEET_PROLOGUE.toByteArray(Charsets.UTF_8))
        sheetOpen = true
    }

    override fun writeHeader() {
        open()
        appendRow(READABLE_EXPORT_HEADERS.map { Cell.text(it) })
    }

    override fun writeRow(row: ReadableExportRowView) {
        appendRow(
            listOf(
                Cell.text(row.occurredAtText),
                Cell.text(row.statisticsAtText),
                Cell.text(row.kindLabel),
                if (row.presentationResolved) Cell.numeric(row.amountText) else Cell.text(row.amountText),
                Cell.text(row.currencyCode),
                Cell.text(row.account1Name),
                Cell.text(row.account2Name),
                Cell.text(row.categoryPathText),
                Cell.text(row.noteText),
                Cell.text(row.sourceLabel),
            ),
        )
    }

    /** Closes `sheetData`/worksheet, finalizes the zip, and commits the target exactly once. */
    override fun commit() {
        check(sheetOpen) { "sheet1.xml was never opened" }
        zip.write(SHEET_EPILOGUE.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        sheetOpen = false
        zip.finish()
        sink.commit()
    }

    /** Idempotent abandon of an uncommitted target; the sink is closed once, only here. */
    override fun close() {
        if (closed) return
        closed = true
        runCatching {
            if (sheetOpen) {
                // An abandoned half-product must still be well-formed XML: the abort-path
                // vectors read the streamed rows back through BoundedXlsxReader, and a sheet
                // without its epilogue is a parse error. Closing sheetData/worksheet here does
                // NOT claim success — the target is still uncommitted and the sink is abandoned.
                zip.write(SHEET_EPILOGUE.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                sheetOpen = false
            }
        }
        runCatching { zip.close() }
        runCatching { sink.close() }
    }

    private fun appendRow(cells: List<Cell>) {
        if (!sheetOpen) open()
        val builder = StringBuilder(ROW_CAPACITY_HINT)
        builder.append("<row r=\"")
        builder.append(nextRowIndex)
        builder.append("\">")
        cells.forEachIndexed { columnIndex, cell ->
            builder.append("<c r=\"")
            builder.append(cellReference(nextRowIndex, columnIndex))
            builder.append('"')
            when (cell) {
                is Cell.Text -> {
                    builder.append(" t=\"inlineStr\"><is><t>")
                    builder.append(escapeXml(cell.value))
                    builder.append("</t></is></c>")
                }

                is Cell.Numeric -> {
                    builder.append("><v>")
                    builder.append(escapeXml(cell.value))
                    builder.append("</v></c>")
                }
            }
        }
        builder.append("</row>")
        val bytes = builder.toString().toByteArray(Charsets.UTF_8)
        zip.write(bytes, 0, bytes.size)
        nextRowIndex++
        rowsWritten++
    }

    private fun writeStaticPart(content: String) {
        val entry = ZipEntry(STATIC_PARTS[staticPartCursor])
        staticPartCursor++
        entry.time = 0L
        zip.putNextEntry(entry)
        val bytes = content.toByteArray(Charsets.UTF_8)
        zip.write(bytes, 0, bytes.size)
        zip.closeEntry()
    }

    /** One cell of a rendered row: an inline text cell or an exact-text numeric cell. */
    private sealed class Cell {
        class Text(
            val value: String,
        ) : Cell()

        class Numeric(
            val value: String,
        ) : Cell()

        companion object {
            fun text(value: String): Cell = Text(value)

            fun numeric(value: String): Cell = Numeric(value)
        }
    }

    /** Bridges the zip stream to the self-owned sink without ever re-closing it here. */
    private class SinkOutputStream(
        private val sink: ReadableExportSink,
    ) : OutputStream() {
        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            sink.write(bytes, offset, length)
        }

        override fun write(value: Int) {
            // The JDK zip internals are byte-array-only in practice, but a single-byte write
            // must still reach the sink through the same byte face instead of failing the
            // whole export (defensive: WRITE_FAILED on a synthetic write shape is a worse
            // contract than a one-byte bridge).
            sink.write(byteArrayOf(value.toByte()), 0, 1)
        }

        override fun close() {
            // The zip's close must NOT close the sink: commit() runs after finish() and the
            // sink lifecycle belongs to this writer's [close]. A no-op close keeps that order.
        }
    }

    companion object {
        /** The static four parts of D-E3/D-E7; with `sheet1.xml` the package is EXACTLY six parts. */
        val STATIC_PARTS: List<String> =
            listOf(
                "[Content_Types].xml",
                "_rels/.rels",
                "xl/workbook.xml",
                "xl/_rels/workbook.xml.rels",
            )

        const val SHEET_PART: String = "xl/worksheets/sheet1.xml"

        private const val XML_DECLARATION: String = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        private const val ROW_CAPACITY_HINT: Int = 256

        private val CONTENT_TYPES_XML: String =
            XML_DECLARATION +
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
                "</Types>"

        private val ROOT_RELS_XML: String =
            XML_DECLARATION +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" " +
                "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" " +
                "Target=\"xl/workbook.xml\"/>" +
                "</Relationships>"

        private val WORKBOOK_XML: String =
            XML_DECLARATION +
                "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                "<sheets><sheet name=\"" +
                escapeXml(READABLE_EXPORT_SHEET_NAME) +
                "\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"

        private val WORKBOOK_RELS_XML: String =
            XML_DECLARATION +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" " +
                "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" " +
                "Target=\"worksheets/sheet1.xml\"/>" +
                "</Relationships>"

        private val SHEET_PROLOGUE: String =
            XML_DECLARATION +
                "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
                "<sheetData>"

        private const val SHEET_EPILOGUE: String = "</sheetData></worksheet>"

        /** XML-escapes text/attribute content with the five predefined entities (the template family). */
        fun escapeXml(value: String): String =
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

        /** 0-based column index -> spreadsheet letters (0 = A, 25 = Z, 26 = AA; the template family). */
        fun columnName(columnIndex: Int): String {
            var remaining = columnIndex
            val letters = StringBuilder()
            while (remaining >= 0) {
                letters.append(('A' + remaining % 26))
                remaining = remaining / 26 - 1
            }
            return letters.reverse().toString()
        }

        /** A1 reference for a (1-based row, 0-based column) pair, e.g. (1, 0) = "A1", (4, 9) = "J4". */
        fun cellReference(
            rowIndex: Int,
            columnIndex: Int,
        ): String = columnName(columnIndex) + rowIndex
    }
}
