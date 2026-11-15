package com.unifiedledger.ui

import com.unifiedledger.application.export.readable.ReadableLedgerExportFailure
import com.unifiedledger.application.export.readable.ReadableLedgerExportResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P8-05 批 b (D-331 spec sections 4 批 b / V-7): the pure presentation decisions of the
 * readable-export surface. Every frozen copy obligation is pinned verbatim: U4's purpose note
 * (the surface's FIRST line), D-E11's page-level-consistency declaration, D-E9's counting
 * progress row, D-E10's empty-ledger 已导出 0 笔 success banner, §6.8 F-3's remnant hint on every
 * artifact-carrying outcome (the successes too — R8's 横幅明示 obligation), and the entry gate's
 * three-state wiring discipline. The D-E7 discipline is asserted structurally: no produced text
 * carries a file name, path, id or URI.
 */
class P503ReadableExportPresentationTest {
    // ---- entry gate ----

    @Test
    fun theDrawerEntryIsVisibleOnlyWhenTheReadableExportSurfaceIsWired() {
        assertTrue(readableExportEntryVisible(readableExportWired = true))
        assertFalse(readableExportEntryVisible(readableExportWired = false))
    }

    // ---- frozen surface copy ----

    @Test
    fun thePurposeNoteCarriesTheFrozenU4Wording() {
        assertEquals("导出的账目文件用于浏览与对账，不能用于恢复数据；整机备份请用导出备份", READABLE_EXPORT_PURPOSE_NOTE)
    }

    @Test
    fun theConsistencyNoteDeclaresThePageLevelBoundary() {
        assertEquals(
            "导出过程中账本发生变动时，文件可能出现重复或缺失行；导出不承诺时点快照。",
            READABLE_EXPORT_CONSISTENCY_NOTE,
        )
    }

    @Test
    fun theRemnantHintCarriesTheFrozenF3Wording() {
        assertEquals("可能留有不完整的文件，请删除。", READABLE_EXPORT_REMNANT_HINT)
    }

    // ---- progress row (D-E9) ----

    @Test
    fun theProgressRowReadsTheCountingShapeOnceTheDenominatorArrives() {
        assertEquals("正在导出… 200 / 500 笔", readableExportProgressText(exportedRows = 200L, totalRows = 500))
    }

    @Test
    fun theProgressRowReadsThePlainShapeBeforeTheFirstLanding() {
        assertEquals("正在导出…", readableExportProgressText(exportedRows = 0L, totalRows = 0))
    }

    // ---- action gates ----

    @Test
    fun startIsOfferedOnlyWhileNoExportRuns() {
        assertTrue(readableExportStartEnabled(running = false))
        assertFalse(readableExportStartEnabled(running = true))
    }

    @Test
    fun cancelIsReachableExactlyWhileARunIsInFlightAndUnrequested() {
        assertTrue(readableExportCancelEnabled(running = true, cancelRequested = false))
        assertFalse(readableExportCancelEnabled(running = true, cancelRequested = true))
        assertFalse(readableExportCancelEnabled(running = false, cancelRequested = false))
    }

    // ---- typed outcome banners ----

    @Test
    fun noOutcomeRendersNothing() {
        assertNull(readableExportOutcomeText(null))
    }

    @Test
    fun theEmptyLedgerSuccessIsTheHonestZeroBanner() {
        // D-E10: the empty ledger is a legal export — the honest 已导出 0 笔, never a failure.
        val text = readableExportOutcomeText(ReadableLedgerExportResult.Succeeded(exportedRows = 0L, totalRows = 0, suggestedFileName = "unifiedledger-export-20261115.xlsx"))
        assertNotNull(text)
        assertTrue(text.contains("已导出 0 笔"), text)
    }

    @Test
    fun everySuccessCarriesTheRemnantHintAsTheRegisteredSharedBoundaryNotice() {
        // §6.8 R8: EVERY outcome that streamed rows through the sink 横幅明示 the F-3 remnant
        // boundary — the successes too; only RUNTIME_NOT_READY honestly does not.
        val text = assertNotNull(readableExportOutcomeText(ReadableLedgerExportResult.Succeeded(exportedRows = 12L, totalRows = 12, suggestedFileName = "unifiedledger-export-20261115.xlsx")))
        assertTrue(text.contains(READABLE_EXPORT_REMNANT_HINT), text)
    }

    @Test
    fun cancelledCarriesTheRemnantHint() {
        val text = assertNotNull(readableExportOutcomeText(ReadableLedgerExportResult.Cancelled))
        assertTrue(text.contains("取消"), text)
        assertTrue(text.contains(READABLE_EXPORT_REMNANT_HINT), text)
    }

    @Test
    fun everyFailureReasonHasItsOwnNonEmptyLine() {
        val texts = ReadableLedgerExportFailure.entries.map { readableExportFailureText(it) }
        assertTrue(texts.all { it.isNotBlank() })
        // The reasons are distinct copies (no generic "failed" collapse).
        assertEquals(ReadableLedgerExportFailure.entries.size, texts.toSet().size)
    }

    @Test
    fun runtimeNotReadyIsTheOnlyOutcomeWithoutTheRemnantHint() {
        // The lease refusal precedes every read and every target open — there is no artifact.
        val notReady = assertNotNull(readableExportOutcomeText(ReadableLedgerExportResult.Failed(ReadableLedgerExportFailure.RUNTIME_NOT_READY)))
        assertFalse(notReady.contains(READABLE_EXPORT_REMNANT_HINT), notReady)
        ReadableLedgerExportFailure.entries
            .filter { it != ReadableLedgerExportFailure.RUNTIME_NOT_READY }
            .forEach { reason ->
                val text = assertNotNull(readableExportOutcomeText(ReadableLedgerExportResult.Failed(reason)))
                assertTrue(text.contains(READABLE_EXPORT_REMNANT_HINT), text)
            }
    }

    @Test
    fun theNoteTooLongBannerNamesTheFrozenCondition() {
        // §6.4 (F-3 残留文案) + §6.8 R6: the mid-walk over-bound note stops without commit; the
        // banner names the condition in the spec's frozen wording and carries the remnant hint.
        val text = assertNotNull(readableExportOutcomeText(ReadableLedgerExportResult.Failed(ReadableLedgerExportFailure.NOTE_TOO_LONG)))
        assertEquals("备注超长，导出未完成。可能留有不完整的文件，请删除。", text)
        assertTrue(text.contains(READABLE_EXPORT_REMNANT_HINT), text)
    }

    @Test
    fun errorOutcomesAreFlaggedAndTheRemnantCarryingSuccessIsNotAnError() {
        assertTrue(readableExportOutcomeIsError(ReadableLedgerExportResult.Failed(ReadableLedgerExportFailure.WRITE_FAILED)))
        assertFalse(readableExportOutcomeIsError(ReadableLedgerExportResult.Succeeded(exportedRows = 1L, totalRows = 1, suggestedFileName = "unifiedledger-export-20261115.xlsx")))
        assertFalse(readableExportOutcomeIsError(ReadableLedgerExportResult.Cancelled))
        assertFalse(readableExportOutcomeIsError(null))
    }

    @Test
    fun noProducedTextCarriesAFileNamePathIdOrUri() {
        // D-E7: the surface copy never names the ledger's internal identities. The suggested file
        // name rides the RESULT family (display-only), never the banner copy.
        val texts =
            buildList {
                add(READABLE_EXPORT_PURPOSE_NOTE)
                add(READABLE_EXPORT_CONSISTENCY_NOTE)
                add(READABLE_EXPORT_REMNANT_HINT)
                add(readableExportProgressText(exportedRows = 1L, totalRows = 1))
                add(readableExportOutcomeText(ReadableLedgerExportResult.Succeeded(exportedRows = 1L, totalRows = 1, suggestedFileName = "unifiedledger-export-20261115.xlsx"))!!)
                add(readableExportOutcomeText(ReadableLedgerExportResult.Cancelled)!!)
                ReadableLedgerExportFailure.entries.forEach { add(readableExportOutcomeText(ReadableLedgerExportResult.Failed(it))!!) }
            }
        texts.forEach { text ->
            assertFalse(text.contains(".xlsx"), text)
            assertFalse(text.contains(".tmp"), text)
            assertFalse(text.contains("\\"), text)
            // The slash ban targets PATH/URI leakage; the D-E9 frozen counting shape "N / M 笔"
            // (:97) is the spec's own separator, so it is asserted structurally instead: the
            // progress text may carry ONLY the counter slash, never a second one (a URI or a
            // path would not fit that shape).
            if (text.contains("/")) {
                assertEquals(text, readableExportProgressText(exportedRows = 1L, totalRows = 1), "the only legal slash carrier is the frozen counting shape")
            }
        }
    }
}
