package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.LedgerCurrentStateReadPort
import com.unifiedledger.application.LedgerEntryRow
import com.unifiedledger.application.LedgerId
import com.unifiedledger.application.QueryLedgerFlowPage
import com.unifiedledger.application.export.readable.ExportOperationLease
import com.unifiedledger.application.export.readable.LedgerRuntimeLeasePort
import com.unifiedledger.application.export.readable.LedgerRuntimeLeasePortResult
import com.unifiedledger.application.export.readable.ReadableExportSink
import com.unifiedledger.application.export.readable.ReadableLedgerExport
import com.unifiedledger.application.export.readable.ReadableLedgerExportFailure
import com.unifiedledger.application.export.readable.ReadableLedgerExportResult
import com.unifiedledger.application.export.readable.ReadableLedgerXlsxStreamWriter
import com.unifiedledger.application.import.wechat.BoundedXlsxReader
import com.unifiedledger.application.export.readable.parseReadableExportWallClockTextOrNull
import com.unifiedledger.application.export.readable.readableExportKindLabel
import com.unifiedledger.data.db.LedgerDatabase
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Instant

/**
 * P8-05 批 a real-database reconciliation (D-331 spec V-1/V-2/V-6; the
 * `B6PagedSessionReadEquivalenceTest` harness discipline — JdbcSqliteDriver + the real schema +
 * raw-SQL seeds, everything anonymous synthetic). The export use case is assembled with the
 * production read-side collaborators: the real `SqlDelightLedgerCurrentStateReadAdapter` page
 * read, the real `SqlDelightCreationEntryBatchReader` lineage read and the real catalog loader
 * face — no test shortcut replaces a production collaborator on the read side. The synthetic
 * ledger (see [ReadableExportReconciliationSeed]) covers every D-E5 group across the 16 kinds, a
 * voided transaction (V-6: absent from the file and the count), a multi-version correction, a
 * same-second tie, a cross-currency transfer, a fee transfer, the unnamed-account/blank-name
 * placeholders, and an over-bound note on the LAST page (R6 中途检出, no commit — V-4's mid-walk
 * leg on the real database). A dedicated vector pins PREPAID_PURCHASE's GOLDEN rg-11 shape
 * (payment −N / prepaid +N, no category leg) falling through to 「（呈现未定）」 with
 * PRESENTATION_UNRESOLVED and no commit — the registered spec-vs-golden deviation (lead finding).
 *
 * 落点说明 (registered for the batch report): the spec's "jvmTest（真库）" needs the real schema
 * and the real read adapter, which live in THIS module; `ledger-data` already api-depends on
 * `ledger-application`, so the reconciliation runs here — the desktop composition-root variant
 * (the DesktopImportSpineReplayAndL2BoundaryCompositionRootTest precedent) would add no read-side
 * authority this module cannot supply.
 */
class ReadableExportRealLedgerReconciliationTest {
    private val ledgerId = LedgerId("ledger-p805-reconciliation")
    private val clock = LedgerClock { Instant.parse("2026-11-15T02:00:00Z") }

    @Test
    fun theRealDatabaseExportReconcilesRowByRowWithTheEffectivePageSource() {
        val harness = RealLedgerHarness()
        harness.seed()
        val outcome = harness.exportAll(pageSize = 3)
        val succeeded = assertIs<ReadableLedgerExportResult.Succeeded>(outcome.result)

        // V-1: the exported row count equals the real effectiveTransactionCount AND the walked
        // page rows; V-6: the voided transaction is in neither (it is in neither the walk nor
        // the file, and the count excludes it).
        val adapter = harness.readAdapter
        val walkedRows = mutableListOf<LedgerEntryRow>()
        var cursor: LedgerCurrentStateReadPort.LedgerFlowCursor? = null
        do {
            val page = adapter.loadLedgerFlowPage(ledgerId, cursor, 3)
            walkedRows += page.rows
            cursor = page.nextCursor
        } while (cursor != null)
        assertEquals(adapter.effectiveTransactionCount(ledgerId), walkedRows.size)
        assertEquals(walkedRows.size.toLong(), succeeded.exportedRows)
        assertEquals(walkedRows.size, succeeded.totalRows)

        // D-E1 页序直写: file row i+1 equals page row i on both time columns and the kind label.
        // V-2: the wall-clock texts parse back to the persisted instants exactly.
        val sheet = BoundedXlsxReader.read(outcome.bytes).firstSheet
        assertEquals(walkedRows.size, sheet.lastRowNum, "one file row per effective transaction")
        walkedRows.forEachIndexed { index, row ->
            val fileRow = sheet.getRow(index + 1)
            assertNotNull(fileRow)
            val occurredText = assertNotNull(fileRow.getCell(0)).string
            val statisticsText = assertNotNull(fileRow.getCell(1)).string
            assertEquals(
                row.occurredAt,
                parseReadableExportWallClockTextOrNull(assertNotNull(occurredText)),
                "row ${index + 1} 凭证时间 round-trip",
            )
            assertEquals(
                row.statisticsAt,
                parseReadableExportWallClockTextOrNull(assertNotNull(statisticsText)),
                "row ${index + 1} 统计时间 round-trip",
            )
            assertEquals(readableExportKindLabel(row.kind), assertNotNull(fileRow.getCell(2)).string, "row ${index + 1} kind label")
        }
        assertEquals(1, harness.sink.commitCount, "the clean close commits exactly once")
    }

    @Test
    fun theOverboundNoteOnTheLastPageStopsWithoutCommit() {
        val harness = RealLedgerHarness()
        harness.seed()
        harness.seedOversizedNote()
        val outcome = harness.exportAll(pageSize = 3)
        val failed = assertIs<ReadableLedgerExportResult.Failed>(outcome.result)
        assertEquals(ReadableLedgerExportFailure.NOTE_TOO_LONG, failed.reason)
        assertEquals(0, harness.sink.commitCount, "the mid-walk data-condition stop never commits (V-4)")
    }

    @Test
    fun theGoldenPrepaidPurchaseShapeFallsThroughToTheRowPlaceholderAndNeverCommits() {
        // The registered spec-vs-golden deviation (lead finding, D-331): golden rg-11
        // states/main-01 shapes PREPAID_PURCHASE as payment_asset −N + prepaid_asset +N with NO
        // category leg, while the spec's D-E5 group ⑤ lists it under 「金额 = 分类腿」. The
        // implementation follows the GOLDEN shape: the row falls through to the row-level
        // 「（呈现未定）」placeholder, the export stops with PRESENTATION_UNRESOLVED and never
        // commits — pinning this so a later change cannot "fix" it by wrongly re-anchoring
        // PREPAID_PURCHASE onto a category leg (the spec text is corrected by the lead).
        val harness = RealLedgerHarness()
        harness.seed()
        harness.seedPrepaidPurchaseGoldenShape()
        val outcome = harness.exportAll(pageSize = 3)
        val failed = assertIs<ReadableLedgerExportResult.Failed>(outcome.result)
        assertEquals(ReadableLedgerExportFailure.PRESENTATION_UNRESOLVED, failed.reason)
        assertEquals(0, harness.sink.commitCount, "a data-condition stop never commits (V-4)")

        // The streamed row keeps the real kind label, the placeholder TEXT in 金额 (a STRING
        // cell, never an invented amount) and the empty 币种/账户1/账户2/分类 cells.
        val sheet = BoundedXlsxReader.read(outcome.bytes).firstSheet
        val placeholderRow =
            (1..sheet.lastRowNum)
                .mapNotNull { sheet.getRow(it) }
                .firstOrNull { it.getCell(2)?.string == "预付购买" }
        assertNotNull(placeholderRow, "the 预付购买 row was streamed before the stop")
        assertEquals(BoundedXlsxReader.CellType.STRING, assertNotNull(placeholderRow.getCell(3)).type)
        assertEquals("（呈现未定）", assertNotNull(placeholderRow.getCell(3)).string)
        assertEquals("", assertNotNull(placeholderRow.getCell(4)).string)
        assertEquals("", assertNotNull(placeholderRow.getCell(5)).string)
        assertEquals("", assertNotNull(placeholderRow.getCell(6)).string)
        assertEquals("", assertNotNull(placeholderRow.getCell(7)).string)
    }

    // ---------------------------------------------------------------- harness

    /** The local byte-counting sink (the ledger-application test fakes are not on this source set). */
    private class ReconciliationSink : ReadableExportSink {
        val buffer = ByteArrayOutputStream()
        var commitCount = 0
            private set

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            buffer.write(bytes, offset, length)
        }

        override fun commit() {
            commitCount++
        }

        override fun close() {
            // The real writer closes the sink; nothing to observe here.
        }
    }

    private class RealLedgerHarness {
        val path: Path = Files.createTempFile("p805-reconciliation-", ".db").also { Files.deleteIfExists(it) }
        val driver: SqlDriver = JdbcSqliteDriver("jdbc:sqlite:${path.absolutePathString()}")

        init {
            // The DefB discipline: create the REAL schema explicitly, then let the catalog store
            // wire its connection pragmas (foreign_keys ON) before the seeds run.
            LedgerDatabase.Schema.create(driver)
        }

        val database: LedgerDatabase = LedgerDatabase(driver)
        val readAdapter = SqlDelightLedgerCurrentStateReadAdapter(database)
        val creationEntries = SqlDelightCreationEntryBatchReader(database)
        val catalogStore = SqlDelightCatalogStore(database, driver)
        val sink = ReconciliationSink()

        val leasePort =
            object : LedgerRuntimeLeasePort {
                override fun acquireLease(): LedgerRuntimeLeasePortResult = LedgerRuntimeLeasePortResult.Acquired(ExportOperationLease { })
            }

        fun exportAll(pageSize: Int): ReconciliationOutcome {
            val writer = ReadableLedgerXlsxStreamWriter(sink)
            val export =
                ReadableLedgerExport(
                    leasePort = leasePort,
                    flowPage = QueryLedgerFlowPage(readAdapter, ledgerId),
                    creationEntries = creationEntries,
                    catalogProvider = { catalogStore.loadCurrent(ledgerId) },
                    writerProvider = { writer },
                    clock = clock,
                    pageSize = pageSize,
                )
            val result = export.export(ledgerId)
            return ReconciliationOutcome(result, sink.buffer.toByteArray())
        }

        fun seed() {
            ReadableExportReconciliationSeed.seed(driver, ledgerId)
        }

        fun seedPrepaidPurchaseGoldenShape() {
            ReadableExportReconciliationSeed.seedPrepaidPurchaseGoldenShape(driver, ledgerId)
        }

        fun seedOversizedNote() {
            ReadableExportReconciliationSeed.seedOversizedNote(driver, ledgerId)
        }
    }

    private class ReconciliationOutcome(
        val result: ReadableLedgerExportResult,
        val bytes: ByteArray,
    )
}
