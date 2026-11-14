package com.unifiedledger.android

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unifiedledger.application.CatalogCommandResult
import com.unifiedledger.application.ImportFileIntakeInput
import com.unifiedledger.application.ImportFileIntakeOutcome
import com.unifiedledger.application.ImportFormatAvailability
import com.unifiedledger.application.ImportFormatCapabilities
import com.unifiedledger.application.ImportIntakeBatchFailure
import com.unifiedledger.application.ImportIntakeRecordDisposition
import com.unifiedledger.application.ImportPlatformKind
import com.unifiedledger.application.ImportReviewRowsPageResult
import com.unifiedledger.application.import.selfentry.SelfEntryBatchOutcome
import com.unifiedledger.application.import.selfentry.SelfEntryRowResult
import com.unifiedledger.application.import.selfentry.SelfEntryTemplateGenerator
import com.unifiedledger.application.import.selfentry.SelfEntryXlsxParser
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.ui.BoundedFileRead
import com.unifiedledger.ui.ImportTemplateDownloadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * P8-04 i4 (spec `docs/specs/2026-11-14-p8-04-self-entry-import-design.md` §4 i4, V-6): the
 * on-device execution evidence for the SELF_ENTRY_XLSX Android availability flip — the device
 * is the flip's evidence face, exactly the A-04.1 CCB XLS precedent (HSSF there, the generated
 * XLSX template here). The declaration this batch flips lives in
 * `ImportFormatCapabilities.SELF_ENTRY_XLSX`; the flip and this test are one traceable batch.
 *
 * What runs on the Android runtime, over REAL production code only:
 *
 * 1. TEMPLATE WRITE (V-6 下载腿): the production `AndroidImportTemplateTargetPort` — the same
 *    adapter `App.kt`'s `templateSaveLauncher` CreateDocument slot delivers into — generates the
 *    template and writes it to an in-memory target. Only the SAF picker UI is replaced (the
 *    launch closure delivers a synthetic target immediately, the `BackupExportSafMidWrite`
 *    in-memory target-provider shape): the generate/write/close path and the typed
 *    `Succeeded(bytes)` outcome are the real platform code.
 * 2. DEVICE ROUND-TRIP (V-1 设备腿): the written bytes are read back by the production
 *    `SelfEntryXlsxParser` ON DEVICE — the zero-asset-drift invariant holds on the second
 *    runtime, not just on the JVM.
 * 3. THE FLIPPED MATRIX + THE INTAKE GATE (V-6 翻转断言): the fifth entry is declared AVAILABLE
 *    on ANDROID (the assertion lives here, on the device the flip is about), and a real-ledger
 *    intake dispatch of an empty pick proves the preserved (a) gate ADMITS the format — the
 *    typed failure is the parser's `INPUT_DECODE_FAILED`, never `FormatUnavailable`.
 * 4. PICK→INTAKE FULL CHAIN (V-6 全链最小腿): on a real production ledger (the P8-01a V-10
 *    chain-fixture shape: the production `openAndroidStableStorageLedger` over a redirected
 *    root), the L0 bounded read runs through the real `AndroidImportFilePickPort.readBounded`
 *    (the SAF OpenDocument port's read path; only the system picker UI is synthetic), the
 *    composition-root catalog supplier supplies the authoritative snapshot, and the template's
 *    3 example rows land as candidates whose summaries carry the D-19 match basis — the i3a/i3b
 *    capability is alive on the device.
 * 5. DEVICE LARGE-SAMPLE READING (V-7 设备大样本读数, i4b): one RECORDED device reading of a
 *    synthetic self-entry intake at the 1,000-row tier — the timed `intake` lands every row as
 *    a candidate on the device's real ledger, the candidate count is asserted structurally,
 *    and the reading goes into ONE grep-able logcat line. NO DURATION IS ASSERTED (the P8-02
 *    V-8 device-timing discipline): a green run is never read as "fast enough". The 10,000
 *    tier is the jvmTest real-ledger legs' burden (`DesktopImportSpineReplayAndL2Boundary
 *    CompositionRootTest`); the maximum tier's device reading belongs to the Rig/D-7 reading
 *    legs and is NOT registered here.
 *
 * Test data is fully synthetic (the generator's own frozen example rows; the seeded fixture
 * account plus one created 现金 account). Amounts ride the parser's BigDecimal-text contract.
 * Run manually on the managed emulator as the flip's run evidence; CI keeps zero
 * connectedAndroidTest. The Rig line (`AndroidScaleLongInstrumentedTest`, budgets, markers) is
 * untouched by this batch.
 */
@RunWith(AndroidJUnit4::class)
class SelfEntryAndroidInstrumentedTest {
    // ------------------------------------------------------------------ 1+2: template write seam + device round-trip

    @Test
    fun templateWriteThroughTheCreateDocumentSeamRoundTripsThroughTheDeviceParser() {
        val written = ByteArrayOutputStream()
        var delivered: ImportTemplateDownloadResult? = null
        var suggestedName: String? = null
        // The forward-reference shape the BackupExportSafMidWrite rig uses: the launch closure
        // delivers into the port the initializer below assigns (Kotlin cannot capture the val
        // inside its own initializer).
        var port: AndroidImportTemplateTargetPort<Uri>? = null
        val templatePort =
            AndroidImportTemplateTargetPort<Uri>(
                postToMainThread = { block -> block() },
                launchCreateDocument = { name ->
                    suggestedName = name
                    // The SAF CreateDocument UI is replaced by an immediate delivery of a
                    // synthetic target (the in-memory target-provider shape); the adapter's
                    // generate + write + close path below is the real production code.
                    port?.onCreateDocumentResult(Uri.parse("content://synthetic/p804/template.xlsx"))
                },
                openOutputStream = { written },
                onResult = { result -> delivered = result },
            )
        port = templatePort

        templatePort.writeTemplate()

        // D-14: the suggested name is the generator's own versioned constant, never a copy.
        assertEquals(SelfEntryTemplateGenerator.TEMPLATE_FILE_NAME, suggestedName)
        val result = present("template download result", delivered)
        assertEquals(
            "the in-memory write must report the typed success",
            ImportTemplateDownloadResult.Succeeded(written.size()),
            result,
        )
        assertTrue("the written template must be non-empty", written.size() > 0)

        // V-1 设备腿: the device-written bytes read back through the production parser. The
        // PARTIAL is the JVM-pinned shape (SelfEntryXlsxParserJvmTest): the blank 币种 cells are
        // visible inferences — non-blocking notes, never a rejection.
        val parsed = SelfEntryXlsxParser.parse("p804-device-template", written.toByteArray())
        assertEquals(SelfEntryBatchOutcome.PARTIAL, parsed.outcome)
        assertNull(parsed.diagnostic)
        assertEquals(3, parsed.rows.size)
        val expense = present("the expense example row", parsed.rows[0] as? SelfEntryRowResult.Accepted)
        assertEquals("out", expense.facts.directionToken)
        assertEquals(1234L, expense.facts.amountMinor)
        assertEquals("2026-01-01T10:00:00+08:00", expense.facts.occurredAt)
    }

    // ------------------------------------------------------------------ 3: the flipped matrix + the intake gate

    @Test
    fun theFlippedMatrixIsAvailableOnAndroidAndTheIntakeGateAdmitsTheFifthEntry() {
        // The flip's constant-truth assertion, ON the device the flip is about (the evidence
        // face): the declaration the i4 batch changed reads AVAILABLE here.
        assertEquals(
            ImportFormatAvailability.AVAILABLE,
            ImportFormatCapabilities.SELF_ENTRY_XLSX.availabilityOn(ImportPlatformKind.ANDROID),
        )
        P801ChainFixture("gate").use { fixture ->
            fixture.open()
            val facade = fixture.graph.facade
            // The preserved (a) gate must ADMIT the format now: an empty pick dispatches into
            // the parser and returns its typed batch rejection — never FormatUnavailable, and
            // zero spine writes either way (the rejection precedes the dispatch fold).
            val outcome =
                facade.importFileIntake!!.intake(
                    ImportFileIntakeInput(
                        format = ImportFormatCapabilities.SELF_ENTRY_XLSX.identifier,
                        platform = ImportPlatformKind.ANDROID,
                        session = facade.importIntakeSessionFactory()!!,
                        bytes = ByteArray(0),
                    ),
                )
            val rejected = present("the empty-pick intake outcome", outcome as? ImportFileIntakeOutcome.Rejected)
            val failure = present("the typed batch failure", rejected.failure as? ImportIntakeBatchFailure.ParserRejected)
            assertEquals("INPUT_DECODE_FAILED", failure.diagnostic.code)
        }
    }

    // ------------------------------------------------------------------ 4: pick→intake full chain on a real ledger

    @Test
    fun pickToIntakeLandsSelfEntryCandidatesWithTheMatchBasisOnARealLedger() {
        P801ChainFixture("chain").use { fixture ->
            fixture.open()
            val facade = fixture.graph.facade

            // The probe row's funding account (现金) must exist in the supplied snapshot: a real
            // catalog command, then the session refresh the composition roots share.
            val receipt =
                facade
                    .executeCatalogCommand
                    ?.createAccount(facade.ledgerId, "现金", AccountKind.ASSET, expectedCatalogVersion = 1L)
            val accepted = present("the 现金 account must be created", receipt as? CatalogCommandResult.Accepted)
            assertNotNull(accepted.receipt.createdManageableAccountId)
            fixture.graph.catalogSession!!.refresh()

            // The catalog supplier is the composition-root wiring this batch injected; on the
            // device graph it must be alive and hand back the refreshed authoritative snapshot.
            val supplier = present("the composition-root intake catalog supplier", facade.importIntakeCatalog)
            val supplied = present("the supplied authoritative catalog", supplier())

            // The PICK leg: the real SAF OpenDocument port's L0 bounded read (the system picker
            // UI is synthetic — the stream comes from the generated template bytes).
            val templateBytes = SelfEntryTemplateGenerator.generateTemplate()
            val pickPort =
                AndroidImportFilePickPort<Uri>(
                    launchOpenDocument = { },
                    resolveMetadata = { PickedSafFileMetadata(displayName = "template.xlsx", sizeBytes = templateBytes.size.toLong()) },
                    openInputStream = { templateBytes.inputStream() },
                    onResult = { },
                )
            val read = pickPort.readBounded(Uri.parse("content://synthetic/p804/template.xlsx"), templateBytes.size.toLong())
            val bytes = present("the L0 bounded read", read as? BoundedFileRead.Bytes).bytes

            // The INTAKE leg: the production orchestrator over the real ledger.
            val outcome =
                facade.importFileIntake!!.intake(
                    ImportFileIntakeInput(
                        format = ImportFormatCapabilities.SELF_ENTRY_XLSX.identifier,
                        platform = ImportPlatformKind.ANDROID,
                        session = facade.importIntakeSessionFactory()!!,
                        bytes = bytes,
                        catalog = supplied,
                    ),
                )
            val intaken = present("the template intake outcome", outcome as? ImportFileIntakeOutcome.Accepted)
            assertEquals(3, intaken.records.size)
            assertTrue("all three rows must be accepted", intaken.records.all { it.disposition == ImportIntakeRecordDisposition.INTAKE_ACCEPTED })
            assertEquals(3, intaken.newCandidateIds.size)

            // Row 0 (支出, 餐饮/现金): the D-19 match basis rode the device dispatch — the i3a
            // matching capability is alive on the device.
            val expenseBasis = present("the expense row's match basis", intaken.records[0].matchBasis)
            assertEquals("餐饮", expenseBasis.categorySourceText)
            assertEquals("餐饮", expenseBasis.categoryTargetName)
            assertEquals("exact", expenseBasis.categoryMatchMethod)
            assertEquals("现金", expenseBasis.fundingAccountSourceText)
            assertEquals("现金", expenseBasis.fundingAccountTargetName)
            assertNull(expenseBasis.fundingAccountUnmatchedReason)
            assertNotNull(intaken.records[0].noteText)

            // Row 2 (转账, 现金→银行卡): 银行卡 does not exist in the minimal fixture ledger, so
            // the to leg is typed-unmatched — and the WHOLE row is Unmatched (foldTransfer),
            // whose basis carries reason tokens only: every target name is null even where the
            // from leg itself resolved (the same projection semantics the JVM pins). The
            // candidate still lands — matching never decides, D-017.
            val transferBasis = present("the transfer row's match basis", intaken.records[2].matchBasis)
            assertTrue("the transfer row carries the unconsumed category flag", transferBasis.unconsumedCategoryText)
            assertNull(transferBasis.fromAccountTargetName)
            assertNull(transferBasis.toAccountTargetName)
            assertEquals("ACCOUNT_NOT_FOUND", transferBasis.toAccountUnmatchedReason)

            // The candidates are readable on the real ledger (候选落地 evidence).
            val rows =
                facade
                    .queryImportReviewRows!!
                    .queryPage(facade.ledgerId, 10L, null)
            val page = present("the review rows page", rows as? ImportReviewRowsPageResult.Page)
            assertEquals(3, page.rows.size)
        }
    }

    // ------------------------------------------------------------------ 5: the V-7 device large-sample reading (i4b)

    /**
     * V-7 设备大样本读数 (i4b): a 1,000-row synthetic self-entry intake on the device's real
     * production ledger. The timed call is the production `intake` dispatch end-to-end (parse
     * + spine transactions); the only asserted numbers are STRUCTURAL (the row and candidate
     * counts, one frozen page read). The wall-clock reading rides one logcat line
     * (`P804-V7DEV`) and is never asserted: a green run means "correct", never "fast enough"
     * (the P8-02 V-8 device-timing discipline).
     *
     * Scale rationale: 1,000 rows keeps the case safely inside the ordinary device regression
     * wall clock while exercising the same parse/spine chain the JVM's 10,000-row real-ledger
     * legs pin; the 10,000 tier on a device belongs to the Rig/D-7 reading legs, not this
     * batch.
     */
    @Test
    fun aThousandRowSyntheticIntakeLandsOnTheDeviceLedgerWithARecordedReading() {
        P801ChainFixture("v7dev").use { fixture ->
            fixture.open()
            val facade = fixture.graph.facade

            val bytes = largeSyntheticSelfEntryWorkbook(LARGE_SAMPLE_ROWS)

            val start = System.nanoTime()
            val outcome =
                facade.importFileIntake!!.intake(
                    ImportFileIntakeInput(
                        format = ImportFormatCapabilities.SELF_ENTRY_XLSX.identifier,
                        platform = ImportPlatformKind.ANDROID,
                        session = facade.importIntakeSessionFactory()!!,
                        bytes = bytes,
                    ),
                )
            val intakeMs = (System.nanoTime() - start) / 1_000_000.0

            val intaken = present("the large-sample intake outcome", outcome as? ImportFileIntakeOutcome.Accepted)
            assertEquals(LARGE_SAMPLE_ROWS, intaken.records.size)
            assertTrue(
                "every row must land as a new intake",
                intaken.records.all { it.disposition == ImportIntakeRecordDisposition.INTAKE_ACCEPTED },
            )
            assertEquals(LARGE_SAMPLE_ROWS, intaken.newCandidateIds.size)

            // The candidates are readable on the device's real ledger: one frozen-size page
            // read proves the rows landed without walking all 1,000.
            val rows =
                facade
                    .queryImportReviewRows!!
                    .queryPage(facade.ledgerId, 10L, null)
            val page = present("the large-sample review page", rows as? ImportReviewRowsPageResult.Page)
            assertEquals(10, page.rows.size)

            // The recorded reading: one grep-able logcat line (no duration assertion).
            val line =
                "$PREFIX rows=$LARGE_SAMPLE_ROWS intakeMs=" +
                    String.format(Locale.ROOT, "%.1f", intakeMs) +
                    " candidates=${intaken.newCandidateIds.size} pageSize=${page.rows.size}"
            for (chunk in line.chunked(LOGCAT_CHUNK)) {
                Log.i(TAG, chunk)
            }
        }
    }

    /**
     * A 1,000-row synthetic 9-column workbook, string-built: the generator's six-part minimal
     * OOXML shape, the frozen 9-string header verbatim from `SelfEntryTemplateGenerator`
     * (never a hand copy), every row a distinct 日期时间 and 金额, and the 币种 cell always
     * filled (no inference diagnostics). The amount column is an exact-text numeric cell —
     * the generator's own shape.
     */
    private fun largeSyntheticSelfEntryWorkbook(rowCount: Int): ByteArray {
        val sharedStrings = LinkedHashSet<String>()
        sharedStrings.addAll(HEADERS)
        fun shared(text: String): String {
            sharedStrings.add(text)
            return "<c t=\"s\"><v>${sharedStrings.toList().indexOf(text)}</v></c>"
        }
        val dataRows = StringBuilder()
        for (ordinal in 0 until rowCount) {
            val month = (3 + ordinal % 10).toString().padStart(2, '0')
            val day = (1 + ordinal / 10 % 28).toString().padStart(2, '0')
            dataRows.append("<row r=\"").append(ordinal + 2).append("\">")
            dataRows.append(shared("支出"))
            dataRows.append(shared("2026-$month-$day 08:00:00"))
            dataRows.append("<c><v>").append(ordinal + 1).append(".").append((ordinal % 100).toString().padStart(2, '0')).append("</v></c>")
            dataRows.append(shared("CNY"))
            dataRows.append(shared("餐饮"))
            dataRows.append(shared("现金"))
            dataRows.append("</row>")
        }
        val sharedStringsXml =
            StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"")
                .append(sharedStrings.size)
                .append("\" uniqueCount=\"")
                .append(sharedStrings.size)
                .append("\">")
        for (text in sharedStrings) {
            sharedStringsXml.append("<si><t>").append(text).append("</t></si>")
        }
        sharedStringsXml.append("</sst>")
        val sheetXml =
            StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
                .append("<row r=\"1\">")
                .append(HEADERS.joinToString("") { header -> shared(header) })
                .append("</row>")
                .append(dataRows)
                .append("</sheetData></worksheet>")
        val parts =
            linkedMapOf(
                CONTENT_TYPES_PART to CONTENT_TYPES_XML.toByteArray(Charsets.UTF_8),
                WORKBOOK_PART to WORKBOOK_XML.toByteArray(Charsets.UTF_8),
                WORKBOOK_RELS_PART to WORKBOOK_RELS_XML.toByteArray(Charsets.UTF_8),
                ROOT_RELS_PART to ROOT_RELS_XML.toByteArray(Charsets.UTF_8),
                SHARED_STRINGS_PART to sharedStringsXml.toString().toByteArray(Charsets.UTF_8),
                SHEET_PART to sheetXml.toString().toByteArray(Charsets.UTF_8),
            )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            parts.forEach { (name, partBytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(partBytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** JUnit assertNotNull returns void; this returns the value for Kotlin expression use. */
    private fun <T> present(
        message: String,
        value: T?,
    ): T {
        assertNotNull(message, value)
        return value!!
    }

    private companion object {
        const val TAG = "UnifiedLedger"
        const val PREFIX = "P804-V7DEV"
        const val LOGCAT_CHUNK = 1_000

        /** The device large-sample tier (i4b): the CI wall-clock discipline's safe row count. */
        const val LARGE_SAMPLE_ROWS = 1_000

        const val CONTENT_TYPES_PART = "[Content_Types].xml"
        const val ROOT_RELS_PART = "_rels/.rels"
        const val WORKBOOK_PART = "xl/workbook.xml"
        const val WORKBOOK_RELS_PART = "xl/_rels/workbook.xml.rels"
        const val SHARED_STRINGS_PART = "xl/sharedStrings.xml"
        const val SHEET_PART = "xl/worksheets/sheet1.xml"

        /** The frozen header strings are the generator's own constants (never hand copies). */
        val HEADERS: List<String> = SelfEntryTemplateGenerator.HEADERS.toList()

        const val CONTENT_TYPES_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
                "<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>" +
                "</Types>"

        const val ROOT_RELS_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
                "</Relationships>"

        const val WORKBOOK_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                "<sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"

        const val WORKBOOK_RELS_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
                "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/>" +
                "</Relationships>"
    }
}
