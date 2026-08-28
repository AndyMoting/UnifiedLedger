package com.unifiedledger.application.import.ccb

import com.unifiedledger.application.IMPORT_FUNDING_RULE_LEGACY_SETTLED
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportFundingState
import com.unifiedledger.application.ImportRecordKind
import com.unifiedledger.application.ImportSourceFacts
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BP-01 CCB parser oracle (frozen spec
 * docs/specs/2026-08-28-bank-import-cmb-ccb-design.md, sections 4.3/4.4/5.1):
 * P-31..P-49 per-row and whole-batch assertions, P-50..P-53 variant batches,
 * P-54 CCB ascending balance-chain vector, and P-55 privacy disjointness.
 *
 * Inputs are the anonymous synthetic fixture files under tests/fixtures/ generated
 * from the frozen spec tables; every value is pinned in the spec, no real data.
 * record_ordinal = 0-based data-row order (B1 -> 0 ... B14 -> 17).
 */
class CcbBillParserJvmTest {
    private val inputRef = "batch-bp01-ccb-a"

    private fun repositoryRoot(): Path {
        var candidate = Path.of(System.getProperty("user.dir"))
        repeat(8) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate
            candidate = candidate.parent ?: error("repository root not found")
        }
        error("repository root not found")
    }

    private fun fixtureBytes(name: String): ByteArray = Files.readAllBytes(repositoryRoot().resolve("tests/fixtures").resolve(name))

    private fun parseFixture(
        ref: String,
        name: String,
    ): CcbBatchResult = CcbBillParser.parse(ref, fixtureBytes(name))

    private fun accepted(
        rows: List<CcbRowResult>,
        ordinal: Int,
    ): CcbRowResult.Accepted {
        val row = rows.first { it.recordOrdinal == ordinal }
        return assertIs<CcbRowResult.Accepted>(row)
    }

    private fun rejected(
        rows: List<CcbRowResult>,
        ordinal: Int,
    ): CcbRowResult.Rejected {
        val row = rows.first { it.recordOrdinal == ordinal }
        return assertIs<CcbRowResult.Rejected>(row)
    }

    private fun assertDiagnostic(
        diagnostic: CcbDiagnostic,
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

    private fun outputStrings(result: CcbBatchResult): List<String> =
        result.rows.flatMap { row ->
            when (row) {
                is CcbRowResult.Accepted ->
                    listOf(
                        row.facts.amountMinor.toString(),
                        row.facts.currencyCode,
                        row.facts.currencyPrecision.toString(),
                        row.facts.occurredAt,
                        row.facts.directionToken,
                        row.facts.statusToken ?: "",
                    )
                is CcbRowResult.Rejected -> emptyList()
            } +
                row.diagnostics.flatMap {
                    listOf(it.code, it.severity, it.scope, it.inputRef, it.recordOrdinal?.toString() ?: "", it.fieldRole ?: "")
                }
        }

    // ---- P-31..P-48: CCB main batch per-row ----

    @Test // P-31
    fun b01OrdinaryExpenseMatchesFrozenFacts() {
        val result = parseFixture(inputRef, "batch-bp01-ccb-a.xls")
        val b01 = accepted(result.rows, 0)
        assertEquals(ImportRecordKind.ORDINARY_FLOW_SOURCE, b01.recordKind)
        assertEquals(
            ImportSourceFacts(1280, "CNY", 2, "2026-08-25T00:00:00+08:00", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1),
            b01.facts,
        )
        assertEquals(ImportCompleteness.VALID_COMPLETE, b01.completeness)
        assertEquals(emptyList(), b01.diagnostics)
    }

    @Test // P-32
    fun b02OrdinaryExpenseMatchesFrozenFacts() {
        val result = parseFixture(inputRef, "batch-bp01-ccb-a.xls")
        val b02 = accepted(result.rows, 1)
        assertEquals(
            ImportSourceFacts(3350, "CNY", 2, "2026-08-25T00:00:00+08:00", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1),
            b02.facts,
        )
        assertEquals(ImportCompleteness.VALID_COMPLETE, b02.completeness)
        assertEquals(emptyList(), b02.diagnostics)
    }

    @Test // P-33
    fun b03OrdinaryIncomeMatchesFrozenFacts() {
        val result = parseFixture(inputRef, "batch-bp01-ccb-a.xls")
        val b03 = accepted(result.rows, 2)
        assertEquals(
            ImportSourceFacts(1000, "CNY", 2, "2026-08-25T00:00:00+08:00", "in", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1),
            b03.facts,
        )
        assertEquals(ImportCompleteness.VALID_COMPLETE, b03.completeness)
        assertEquals(emptyList(), b03.diagnostics)
    }

    @Test // P-34
    fun b04TransferOutMatchesFrozenFacts() {
        val result = parseFixture(inputRef, "batch-bp01-ccb-a.xls")
        val b04 = accepted(result.rows, 3)
        assertEquals(ImportRecordKind.TRANSFER_FLOW_SOURCE, b04.recordKind)
        assertEquals(
            ImportSourceFacts(100, "CNY", 2, "2026-08-26T00:00:00+08:00", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1),
            b04.facts,
        )
        assertEquals(ImportCompleteness.VALID_COMPLETE, b04.completeness)
        assertEquals(emptyList(), b04.diagnostics)
    }

    @Test // P-35
    fun b05TransferInMatchesFrozenFacts() {
        val result = parseFixture(inputRef, "batch-bp01-ccb-a.xls")
        val b05 = accepted(result.rows, 4)
        assertEquals(ImportRecordKind.TRANSFER_FLOW_SOURCE, b05.recordKind)
        assertEquals(
            ImportSourceFacts(750, "CNY", 2, "2026-08-26T00:00:00+08:00", "in", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1),
            b05.facts,
        )
        assertEquals(ImportCompleteness.VALID_COMPLETE, b05.completeness)
        assertEquals(emptyList(), b05.diagnostics)
    }

    @Test // P-36
    fun b06TransferOutMatchesFrozenFacts() {
        val result = parseFixture(inputRef, "batch-bp01-ccb-a.xls")
        val b06 = accepted(result.rows, 5)
        assertEquals(ImportRecordKind.TRANSFER_FLOW_SOURCE, b06.recordKind)
        assertEquals(
            ImportSourceFacts(101, "CNY", 2, "2026-08-27T00:00:00+08:00", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1),
            b06.facts,
        )
        assertEquals(ImportCompleteness.VALID_COMPLETE, b06.completeness)
        assertEquals(emptyList(), b06.diagnostics)
    }

    @Test // P-37
    fun b07OrdinaryExpenseMatchesFrozenFacts() {
        val result = parseFixture(inputRef, "batch-bp01-ccb-a.xls")
        val b07 = accepted(result.rows, 6)
        assertEquals(
            ImportSourceFacts(1, "CNY", 2, "2026-08-28T00:00:00+08:00", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1),
            b07.facts,
        )
        assertEquals(ImportCompleteness.VALID_COMPLETE, b07.completeness)
        assertEquals(emptyList(), b07.diagnostics)
    }

    @Test // P-38
    fun b08ZeroAmountDirectionUnresolvedValidIncomplete() {
        val result = parseFixture(inputRef, "batch-bp01-ccb-a.xls")
        val b08 = accepted(result.rows, 7)
        assertEquals(
            ImportSourceFacts(0, "CNY", 2, "2026-08-28T00:00:00+08:00", "+", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1),
            b08.facts,
        )
        assertEquals(ImportCompleteness.VALID_INCOMPLETE, b08.completeness)
        assertEquals(1, b08.diagnostics.size)
        assertDiagnostic(b08.diagnostics[0], "REQUIRED_FACT_UNRESOLVED", "incomplete", "field", 7, "direction")
    }

    @Test // P-39
    fun b09UnknownSummaryRejectedFailClosed() {
        val result = parseFixture(inputRef, "batch-bp01-ccb-a.xls")
        val b09 = rejected(result.rows, 8)
        assertDiagnostic(b09.diagnostics.single(), "SPINE_CCB_UNKNOWN_TOKEN", "unsupported", "record", 8)
    }

    @Test // P-40
    fun b10RemarkRefundMarkerRejectedByJudgmentOrderOne() {
        val result = parseFixture(inputRef, "batch-bp01-ccb-a.xls")
        val b10 = rejected(result.rows, 9)
        assertDiagnostic(b10.diagnostics.single(), "SPINE_CCB_REFUND_UNSUPPORTED", "unsupported", "record", 9)
    }

    @Test // P-41
    fun b11BalanceContinuityMismatchKeepsRecordValidComplete() {
        val result = parseFixture(inputRef, "batch-bp01-ccb-a.xls")
        val b11 = accepted(result.rows, 10)
        assertEquals(
            ImportSourceFacts(750, "CNY", 2, "2026-08-28T00:00:00+08:00", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1),
            b11.facts,
        )
        assertEquals(ImportCompleteness.VALID_COMPLETE, b11.completeness)
        assertEquals(1, b11.diagnostics.size)
        assertDiagnostic(b11.diagnostics[0], "SPINE_BANK_BALANCE_CONTINUITY", "note", "record", 10)
    }

    @Test // P-42
    fun b15TransferOutMatchesFrozenFacts() {
        val result = parseFixture(inputRef, "batch-bp01-ccb-a.xls")
        val b15 = accepted(result.rows, 11)
        assertEquals(ImportRecordKind.TRANSFER_FLOW_SOURCE, b15.recordKind)
        assertEquals(
            ImportSourceFacts(200, "CNY", 2, "2026-08-28T00:00:00+08:00", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1),
            b15.facts,
        )
        assertEquals(ImportCompleteness.VALID_COMPLETE, b15.completeness)
        assertEquals(emptyList(), b15.diagnostics)
    }

    @Test // P-43
    fun b16TransferInMatchesFrozenFacts() {
        val result = parseFixture(inputRef, "batch-bp01-ccb-a.xls")
        val b16 = accepted(result.rows, 12)
        assertEquals(ImportRecordKind.TRANSFER_FLOW_SOURCE, b16.recordKind)
        assertEquals(
            ImportSourceFacts(2000, "CNY", 2, "2026-08-28T00:00:00+08:00", "in", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1),
            b16.facts,
        )
        assertEquals(ImportCompleteness.VALID_COMPLETE, b16.completeness)
        assertEquals(emptyList(), b16.diagnostics)
    }

    @Test // P-44
    fun b17TransferOutMatchesFrozenFacts() {
        val result = parseFixture(inputRef, "batch-bp01-ccb-a.xls")
        val b17 = accepted(result.rows, 13)
        assertEquals(ImportRecordKind.TRANSFER_FLOW_SOURCE, b17.recordKind)
        assertEquals(
            ImportSourceFacts(5, "CNY", 2, "2026-08-28T00:00:00+08:00", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1),
            b17.facts,
        )
        assertEquals(ImportCompleteness.VALID_COMPLETE, b17.completeness)
        assertEquals(emptyList(), b17.diagnostics)
    }

    @Test // P-45
    fun b18OrdinaryIncomeMatchesFrozenFacts() {
        val result = parseFixture(inputRef, "batch-bp01-ccb-a.xls")
        val b18 = accepted(result.rows, 14)
        assertEquals(
            ImportSourceFacts(880, "CNY", 2, "2026-08-28T00:00:00+08:00", "in", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1),
            b18.facts,
        )
        assertEquals(ImportCompleteness.VALID_COMPLETE, b18.completeness)
        assertEquals(emptyList(), b18.diagnostics)
    }

    @Test // P-46
    fun b12OneDecimalAmountRejectedAsFieldAmountInvalid() {
        val result = parseFixture(inputRef, "batch-bp01-ccb-a.xls")
        val b12 = rejected(result.rows, 15)
        assertDiagnostic(b12.diagnostics.single(), "FIELD_AMOUNT_INVALID", "record_error", "field", 15, "amount")
    }

    @Test // P-47
    fun b13InvalidDateRejectedAsFieldTimeInvalid() {
        val result = parseFixture(inputRef, "batch-bp01-ccb-a.xls")
        val b13 = rejected(result.rows, 16)
        assertDiagnostic(b13.diagnostics.single(), "FIELD_TIME_INVALID", "record_error", "field", 16, "occurred_at")
    }

    @Test // P-48
    fun b14MissingAmountRejectedAsFieldAmountInvalid() {
        val result = parseFixture(inputRef, "batch-bp01-ccb-a.xls")
        val b14 = rejected(result.rows, 17)
        assertDiagnostic(b14.diagnostics.single(), "FIELD_AMOUNT_INVALID", "record_error", "field", 17, "amount")
    }

    // ---- P-49: CCB main batch whole-batch oracle ----

    @Test // P-49
    fun wholeCcbMainBatchIsPartialWithThirteenRecordsAndFrozenDiagnostics() {
        val result = parseFixture(inputRef, "batch-bp01-ccb-a.xls")
        assertEquals(CcbBatchOutcome.PARTIAL, result.outcome)
        assertNull(result.diagnostic)

        assertEquals(18, result.rows.size)
        val acceptedRows = result.rows.filterIsInstance<CcbRowResult.Accepted>()
        assertEquals(13, acceptedRows.size)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 10, 11, 12, 13, 14), acceptedRows.map { it.recordOrdinal })
        assertEquals(12, acceptedRows.count { it.completeness == ImportCompleteness.VALID_COMPLETE })
        assertEquals(1, acceptedRows.count { it.completeness == ImportCompleteness.VALID_INCOMPLETE })
        assertEquals(5, result.rows.count { it is CcbRowResult.Rejected })
        result.rows.filterIsInstance<CcbRowResult.Rejected>().forEach {
            assertEquals(1, it.diagnostics.size, "rejected row carries exactly one diagnostic")
        }

        val multiset =
            result.rows
                .flatMap { row -> row.diagnostics.map { Triple(it.code, it.recordOrdinal, it.fieldRole) } }
                .sortedWith(compareBy({ it.second ?: -1 }, { it.first }))
        assertEquals(
            listOf(
                Triple("REQUIRED_FACT_UNRESOLVED", 7, "direction"),
                Triple("SPINE_CCB_UNKNOWN_TOKEN", 8, null),
                Triple("SPINE_CCB_REFUND_UNSUPPORTED", 9, null),
                Triple("SPINE_BANK_BALANCE_CONTINUITY", 10, null),
                Triple("FIELD_AMOUNT_INVALID", 15, "amount"),
                Triple("FIELD_TIME_INVALID", 16, "occurred_at"),
                Triple("FIELD_AMOUNT_INVALID", 17, "amount"),
            ),
            multiset,
        )
    }

    // ---- P-50..P-53: CCB variant batches ----

    @Test // P-50
    fun bHeaderOffByOneAndTooFewRowsRejectFatal() {
        val offByOne = parseFixture("batch-bp01-ccb-b", "batch-bp01-ccb-b1.xls")
        assertEquals(CcbBatchOutcome.REJECTED, offByOne.outcome)
        assertEquals(0, offByOne.rows.size)
        assertDiagnostic(
            assertIs(offByOne.diagnostic),
            "STRUCTURE_MISMATCH",
            "fatal",
            "structure",
            expectedInputRef = "batch-bp01-ccb-b",
        )

        val tooFew = parseFixture("batch-bp01-ccb-b", "batch-bp01-ccb-b2.xls")
        assertEquals(CcbBatchOutcome.REJECTED, tooFew.outcome)
        assertEquals(0, tooFew.rows.size)
        assertDiagnostic(
            assertIs(tooFew.diagnostic),
            "STRUCTURE_MISMATCH",
            "fatal",
            "structure",
            expectedInputRef = "batch-bp01-ccb-b",
        )
    }

    @Test // P-51
    fun cCurrencyKindHuiIsLegalAndNotPersisted() {
        val result = parseFixture("batch-bp01-ccb-c", "batch-bp01-ccb-c.xls")
        assertEquals(CcbBatchOutcome.COMPLETE, result.outcome)
        assertNull(result.diagnostic)
        val c1 = accepted(result.rows, 0)
        assertEquals(
            ImportSourceFacts(100, "CNY", 2, "2026-08-28T00:00:00+08:00", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1),
            c1.facts,
        )
        assertEquals(ImportCompleteness.VALID_COMPLETE, c1.completeness)
        assertEquals(emptyList(), c1.diagnostics)
        assertEquals(2, result.rows.size)
    }

    @Test // P-52
    fun dSequenceGapsAndNonMonotonicLegalNonIntegerRejected() {
        val gaps = parseFixture("batch-bp01-ccb-d", "batch-bp01-ccb-d1.xls")
        assertEquals(CcbBatchOutcome.COMPLETE, gaps.outcome)
        assertEquals(3, gaps.rows.size)
        gaps.rows.forEach { assertIs<CcbRowResult.Accepted>(it) }

        val nonInteger = parseFixture("batch-bp01-ccb-d", "batch-bp01-ccb-d2.xls")
        assertEquals(CcbBatchOutcome.PARTIAL, nonInteger.outcome)
        assertEquals(2, nonInteger.rows.size)
        assertIs<CcbRowResult.Accepted>(nonInteger.rows[0])
        val bad = assertIs<CcbRowResult.Rejected>(nonInteger.rows[1])
        assertDiagnostic(bad.diagnostics.single(), "STRUCTURE_MISMATCH", "fatal", "record", 1, expectedInputRef = "batch-bp01-ccb-d")
    }

    @Test // P-53
    fun eNumericCellsDecodeToExactDecimalText() {
        val result = parseFixture("batch-bp01-ccb-e", "batch-bp01-ccb-e.xls")
        assertEquals(CcbBatchOutcome.COMPLETE, result.outcome)
        assertNull(result.diagnostic)
        val e1 = accepted(result.rows, 0)
        assertEquals(
            ImportSourceFacts(1280, "CNY", 2, "2026-08-28T00:00:00+08:00", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1),
            e1.facts,
        )
        assertEquals(ImportCompleteness.VALID_COMPLETE, e1.completeness)
        assertEquals(emptyList(), e1.diagnostics)
        val e2 = accepted(result.rows, 1)
        assertEquals(
            ImportSourceFacts(1000, "CNY", 2, "2026-08-28T00:00:00+08:00", "in", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1),
            e2.facts,
        )
        assertEquals(ImportCompleteness.VALID_COMPLETE, e2.completeness)
        assertEquals(emptyList(), e2.diagnostics)
    }

    // ---- P-54: CCB ascending balance-chain vector ----

    @Test // P-54 (CCB)
    fun ascendingBalanceChainHoldsAcrossDateBoundariesAndSameDayRows() {
        val rows =
            listOf(
                listOf("1", "消费", "人民币元", "钞", "20240131", "-2.00", "100.00", "合成", "SYN-CCB-VEC-OPP"),
                listOf("2", "消费", "人民币元", "钞", "20240229", "5.00", "105.00", "合成", "SYN-CCB-VEC-OPP"),
                listOf("3", "消费", "人民币元", "钞", "20240229", "1.00", "106.00", "合成", "SYN-CCB-VEC-OPP"),
                listOf("4", "消费", "人民币元", "钞", "20240301", "-3.00", "103.00", "合成", "SYN-CCB-VEC-OPP"),
            )
        val result = CcbBillParser.parse("batch-bp01-ccb-p54", vectorWorkbook(rows))
        assertEquals(CcbBatchOutcome.COMPLETE, result.outcome)
        assertNull(result.diagnostic)
        assertEquals(4, result.rows.size)
        result.rows.forEach { row ->
            assertIs<CcbRowResult.Accepted>(row)
            assertEquals(emptyList(), row.diagnostics)
        }
        assertEquals("2024-02-29T00:00:00+08:00", accepted(result.rows, 1).facts.occurredAt)
        assertEquals("2024-03-01T00:00:00+08:00", accepted(result.rows, 3).facts.occurredAt)
    }

    // ---- P-55: CCB privacy disjointness ----

    @Test // P-55 (CCB)
    fun titleAreaRemarkAndOpponentValuesNeverLeakIntoOutputs() {
        val result = parseFixture(inputRef, "batch-bp01-ccb-a.xls")
        val outputs = outputStrings(result)
        val secrets =
            listOf(
                "SYN-CCB-STATEMENT-TITLE",
                "SYN-CCB-ACCOUNT-5678",
                "SYN-CCB-CUSTOMER",
                "微信零钱提现",
                "合成商户",
                "合成含「退款」",
                "SYN-CCB-OPP-B1",
                "SYN-CCB-OPP-B18",
                "人民币元",
                "钞",
            )
        secrets.forEach { secret ->
            assertTrue(outputs.none { it == secret || it.contains(secret) }, "title/remark/opponent value leaked: $secret")
        }
    }

    private fun vectorWorkbook(rows: List<List<String>>): ByteArray {
        val workbook = HSSFWorkbook()
        try {
            val sheet = workbook.createSheet("Sheet0")
            sheet.createRow(0).createCell(0).setCellValue("SYN-CCB-VEC-TITLE")
            sheet.createRow(1).createCell(0).setCellValue("SYN-CCB-VEC-ACCOUNT")
            sheet.createRow(2).createCell(0).setCellValue("SYN-CCB-VEC-TOTALS")
            CcbSourceTokens.HEADER_TOKENS.forEachIndexed { index, token ->
                // createRow(3) removes and recreates the row on every call, so the row is
                // created once here and only cells are appended per token.
                val headerRow = sheet.getRow(3) ?: sheet.createRow(3)
                headerRow.createCell(index).setCellValue(token)
            }
            rows.forEachIndexed { rowIndex, cells ->
                val row = sheet.createRow(4 + rowIndex)
                cells.forEachIndexed { index, value -> row.createCell(index).setCellValue(value) }
            }
            val out = ByteArrayOutputStream()
            workbook.write(out)
            return out.toByteArray()
        } finally {
            workbook.close()
        }
    }
}
