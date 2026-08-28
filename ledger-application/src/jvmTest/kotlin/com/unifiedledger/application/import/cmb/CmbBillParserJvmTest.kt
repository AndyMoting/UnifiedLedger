package com.unifiedledger.application.import.cmb

import com.unifiedledger.application.IMPORT_FUNDING_RULE_LEGACY_SETTLED
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportFundingState
import com.unifiedledger.application.ImportRecordKind
import com.unifiedledger.application.ImportSourceFacts
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BP-01 CMB parser oracle (frozen spec
 * docs/specs/2026-08-28-bank-import-cmb-ccb-design.md, sections 4.1/4.2/5.1):
 * P-01..P-30 per-row and whole-batch assertions, P-54 CMB descending balance-chain
 * vector, and P-55 privacy disjointness.
 *
 * Inputs are the anonymous synthetic fixture files under tests/fixtures/ generated
 * from the frozen spec tables; every value is pinned in the spec, no real data.
 * record_ordinal = 0-based data-row order (R1 -> 0 ... R17 -> 16).
 */
class CmbBillParserJvmTest {
    private val inputRef = "batch-bp01-cmb-a"

    private val settledFacts = { amount: Long, occurredAt: String, direction: String ->
        ImportSourceFacts(amount, "CNY", 2, occurredAt, direction, "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)
    }

    // ---- Fixture loading ----

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
    ): CmbBatchResult = CmbBillParser.parse(ref, fixtureBytes(name))

    private fun accepted(
        rows: List<CmbRowResult>,
        ordinal: Int,
    ): CmbRowResult.Accepted {
        val row = rows.first { it.recordOrdinal == ordinal }
        return assertIs<CmbRowResult.Accepted>(row)
    }

    private fun rejected(
        rows: List<CmbRowResult>,
        ordinal: Int,
    ): CmbRowResult.Rejected {
        val row = rows.first { it.recordOrdinal == ordinal }
        return assertIs<CmbRowResult.Rejected>(row)
    }

    private fun assertDiagnostic(
        diagnostic: CmbDiagnostic,
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

    private fun outputStrings(result: CmbBatchResult): List<String> =
        result.rows.flatMap { row ->
            when (row) {
                is CmbRowResult.Accepted ->
                    listOf(
                        row.facts.amountMinor.toString(),
                        row.facts.currencyCode,
                        row.facts.currencyPrecision.toString(),
                        row.facts.occurredAt,
                        row.facts.directionToken,
                        row.facts.statusToken ?: "",
                    )
                is CmbRowResult.Rejected -> emptyList()
            } +
                row.diagnostics.flatMap {
                    listOf(it.code, it.severity, it.scope, it.inputRef, it.recordOrdinal?.toString() ?: "", it.fieldRole ?: "")
                }
        }

    // ---- P-01..P-17: CMB main batch per-row ----

    @Test // P-01
    fun r01OrdinaryExpenseMatchesFrozenFacts() {
        val result = parseFixture(inputRef, "batch-bp01-cmb-a.csv")
        val r01 = accepted(result.rows, 0)
        assertEquals(ImportRecordKind.ORDINARY_FLOW_SOURCE, r01.recordKind)
        assertEquals(settledFacts(12850, "2026-08-25T09:00:00+08:00", "out"), r01.facts)
        assertEquals(ImportCompleteness.VALID_COMPLETE, r01.completeness)
        assertEquals(emptyList(), r01.diagnostics)
    }

    @Test // P-02
    fun r02OrdinaryExpenseMatchesFrozenFacts() {
        val result = parseFixture(inputRef, "batch-bp01-cmb-a.csv")
        val r02 = accepted(result.rows, 1)
        assertEquals(settledFacts(1250, "2026-08-24T10:30:00+08:00", "out"), r02.facts)
        assertEquals(ImportCompleteness.VALID_COMPLETE, r02.completeness)
        assertEquals(emptyList(), r02.diagnostics)
    }

    @Test // P-03
    fun r03OrdinaryIncomeMatchesFrozenFacts() {
        val result = parseFixture(inputRef, "batch-bp01-cmb-a.csv")
        val r03 = accepted(result.rows, 2)
        assertEquals(ImportRecordKind.ORDINARY_FLOW_SOURCE, r03.recordKind)
        assertEquals(settledFacts(8800, "2026-08-23T11:15:00+08:00", "in"), r03.facts)
        assertEquals(ImportCompleteness.VALID_COMPLETE, r03.completeness)
        assertEquals(emptyList(), r03.diagnostics)
    }

    @Test // P-04
    fun r04InterestIncomeMatchesFrozenFacts() {
        val result = parseFixture(inputRef, "batch-bp01-cmb-a.csv")
        val r04 = accepted(result.rows, 3)
        assertEquals(settledFacts(300, "2026-08-22T21:00:00+08:00", "in"), r04.facts)
        assertEquals(ImportCompleteness.VALID_COMPLETE, r04.completeness)
        assertEquals(emptyList(), r04.diagnostics)
    }

    @Test // P-05
    fun r05TransferOutMatchesFrozenFacts() {
        val result = parseFixture(inputRef, "batch-bp01-cmb-a.csv")
        val r05 = accepted(result.rows, 4)
        assertEquals(ImportRecordKind.TRANSFER_FLOW_SOURCE, r05.recordKind)
        assertEquals(settledFacts(10000, "2026-08-21T08:05:00+08:00", "out"), r05.facts)
        assertEquals(ImportCompleteness.VALID_COMPLETE, r05.completeness)
        assertEquals(emptyList(), r05.diagnostics)
    }

    @Test // P-06
    fun r06TransferInMatchesFrozenFacts() {
        val result = parseFixture(inputRef, "batch-bp01-cmb-a.csv")
        val r06 = accepted(result.rows, 5)
        assertEquals(ImportRecordKind.TRANSFER_FLOW_SOURCE, r06.recordKind)
        assertEquals(settledFacts(20000, "2026-08-20T18:40:00+08:00", "in"), r06.facts)
        assertEquals(ImportCompleteness.VALID_COMPLETE, r06.completeness)
        assertEquals(emptyList(), r06.diagnostics)
    }

    @Test // P-07
    fun r07TransferOutMatchesFrozenFacts() {
        val result = parseFixture(inputRef, "batch-bp01-cmb-a.csv")
        val r07 = accepted(result.rows, 6)
        assertEquals(ImportRecordKind.TRANSFER_FLOW_SOURCE, r07.recordKind)
        assertEquals(settledFacts(50000, "2026-08-19T12:00:00+08:00", "out"), r07.facts)
        assertEquals(ImportCompleteness.VALID_COMPLETE, r07.completeness)
        assertEquals(emptyList(), r07.diagnostics)
    }

    @Test // P-08
    fun r08TransferInMatchesFrozenFacts() {
        val result = parseFixture(inputRef, "batch-bp01-cmb-a.csv")
        val r08 = accepted(result.rows, 7)
        assertEquals(ImportRecordKind.TRANSFER_FLOW_SOURCE, r08.recordKind)
        assertEquals(settledFacts(51030, "2026-08-18T14:25:00+08:00", "in"), r08.facts)
        assertEquals(ImportCompleteness.VALID_COMPLETE, r08.completeness)
        assertEquals(emptyList(), r08.diagnostics)
    }

    @Test // P-09
    fun r09RefundRejectedByJudgmentOrderOne() {
        val result = parseFixture(inputRef, "batch-bp01-cmb-a.csv")
        val r09 = rejected(result.rows, 8)
        assertDiagnostic(r09.diagnostics.single(), "SPINE_CMB_REFUND_UNSUPPORTED", "unsupported", "record", 8)
    }

    @Test // P-10
    fun r10UnknownTokenRejectedFailClosed() {
        val result = parseFixture(inputRef, "batch-bp01-cmb-a.csv")
        val r10 = rejected(result.rows, 9)
        assertDiagnostic(r10.diagnostics.single(), "SPINE_CMB_UNKNOWN_TOKEN", "unsupported", "record", 9)
    }

    @Test // P-11
    fun r11BalanceContinuityMismatchKeepsRecordValidComplete() {
        val result = parseFixture(inputRef, "batch-bp01-cmb-a.csv")
        val r11 = accepted(result.rows, 10)
        assertEquals(ImportRecordKind.ORDINARY_FLOW_SOURCE, r11.recordKind)
        assertEquals(settledFacts(1000, "2026-08-15T07:20:00+08:00", "out"), r11.facts)
        assertEquals(ImportCompleteness.VALID_COMPLETE, r11.completeness)
        assertEquals(1, r11.diagnostics.size)
        assertDiagnostic(r11.diagnostics[0], "SPINE_BANK_BALANCE_CONTINUITY", "note", "record", 10)
    }

    @Test // P-12
    fun r12OneDecimalAmountRejectedAsFieldAmountInvalid() {
        val result = parseFixture(inputRef, "batch-bp01-cmb-a.csv")
        val r12 = rejected(result.rows, 11)
        assertDiagnostic(r12.diagnostics.single(), "FIELD_AMOUNT_INVALID", "record_error", "field", 11, "amount")
    }

    @Test // P-13
    fun r13InvalidDateRejectedAsFieldTimeInvalid() {
        val result = parseFixture(inputRef, "batch-bp01-cmb-a.csv")
        val r13 = rejected(result.rows, 12)
        assertDiagnostic(r13.diagnostics.single(), "FIELD_TIME_INVALID", "record_error", "field", 12, "occurred_at")
    }

    @Test // P-14
    fun r14BothAmountColumnsFilledRejectedAsConflictingSourceFacts() {
        val result = parseFixture(inputRef, "batch-bp01-cmb-a.csv")
        val r14 = rejected(result.rows, 13)
        assertDiagnostic(r14.diagnostics.single(), "CONFLICTING_SOURCE_FACTS", "record_error", "record", 13)
    }

    @Test // P-15
    fun r15BothAmountColumnsEmptyRejectedAsFieldAmountInvalid() {
        val result = parseFixture(inputRef, "batch-bp01-cmb-a.csv")
        val r15 = rejected(result.rows, 14)
        assertDiagnostic(r15.diagnostics.single(), "FIELD_AMOUNT_INVALID", "record_error", "field", 14, "amount")
    }

    @Test // P-16
    fun r16HugeIncomeBoundaryIsValidComplete() {
        val result = parseFixture(inputRef, "batch-bp01-cmb-a.csv")
        val r16 = accepted(result.rows, 15)
        assertEquals(settledFacts(9999999999, "2026-08-10T15:30:00+08:00", "in"), r16.facts)
        assertEquals(ImportCompleteness.VALID_COMPLETE, r16.completeness)
        assertEquals(emptyList(), r16.diagnostics)
    }

    @Test // P-17
    fun r17ZeroExpenseIsValidComplete() {
        val result = parseFixture(inputRef, "batch-bp01-cmb-a.csv")
        val r17 = accepted(result.rows, 16)
        assertEquals(settledFacts(0, "2026-08-09T08:00:00+08:00", "out"), r17.facts)
        assertEquals(ImportCompleteness.VALID_COMPLETE, r17.completeness)
        assertEquals(emptyList(), r17.diagnostics)
    }

    // ---- P-18: CMB main batch whole-batch oracle ----

    @Test // P-18
    fun wholeCmbMainBatchIsPartialWithElevenRecordsAndFrozenDiagnostics() {
        val result = parseFixture(inputRef, "batch-bp01-cmb-a.csv")
        assertEquals(CmbBatchOutcome.PARTIAL, result.outcome)
        assertNull(result.diagnostic)

        assertEquals(17, result.rows.size)
        val acceptedRows = result.rows.filterIsInstance<CmbRowResult.Accepted>()
        assertEquals(11, acceptedRows.size)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 10, 15, 16), acceptedRows.map { it.recordOrdinal })
        assertTrue(acceptedRows.all { it.completeness == ImportCompleteness.VALID_COMPLETE })
        assertEquals(6, result.rows.count { it is CmbRowResult.Rejected })
        result.rows.filterIsInstance<CmbRowResult.Rejected>().forEach {
            assertEquals(1, it.diagnostics.size, "rejected row carries exactly one diagnostic")
        }

        val multiset =
            result.rows
                .flatMap { row -> row.diagnostics.map { Triple(it.code, it.recordOrdinal, it.fieldRole) } }
                .sortedWith(compareBy({ it.second ?: -1 }, { it.first }))
        assertEquals(
            listOf(
                Triple("SPINE_CMB_REFUND_UNSUPPORTED", 8, null),
                Triple("SPINE_CMB_UNKNOWN_TOKEN", 9, null),
                Triple("SPINE_BANK_BALANCE_CONTINUITY", 10, null),
                Triple("FIELD_AMOUNT_INVALID", 11, "amount"),
                Triple("FIELD_TIME_INVALID", 12, "occurred_at"),
                Triple("CONFLICTING_SOURCE_FACTS", 13, null),
                Triple("FIELD_AMOUNT_INVALID", 14, "amount"),
            ),
            multiset,
        )
    }

    // ---- P-19..P-30: CMB variant batches ----

    @Test // P-19
    fun b1MissingHeaderColumnRejectsBatchFatal() {
        val result = parseFixture("batch-bp01-cmb-b1", "batch-bp01-cmb-b1.csv")
        assertEquals(CmbBatchOutcome.REJECTED, result.outcome)
        assertEquals(0, result.rows.size)
        assertDiagnostic(
            assertIs(result.diagnostic),
            "STRUCTURE_MISMATCH",
            "fatal",
            "structure",
            expectedInputRef = "batch-bp01-cmb-b1",
        )
    }

    @Test // P-20
    fun b2ExtraHeaderColumnRejectsBatchFatal() {
        val result = parseFixture("batch-bp01-cmb-b2", "batch-bp01-cmb-b2.csv")
        assertEquals(CmbBatchOutcome.REJECTED, result.outcome)
        assertEquals(0, result.rows.size)
        assertDiagnostic(
            assertIs(result.diagnostic),
            "STRUCTURE_MISMATCH",
            "fatal",
            "structure",
            expectedInputRef = "batch-bp01-cmb-b2",
        )
    }

    @Test // P-21
    fun b3MisplacedHeaderColumnsRejectBatchFatal() {
        val result = parseFixture("batch-bp01-cmb-b3", "batch-bp01-cmb-b3.csv")
        assertEquals(CmbBatchOutcome.REJECTED, result.outcome)
        assertEquals(0, result.rows.size)
        assertDiagnostic(
            assertIs(result.diagnostic),
            "STRUCTURE_MISMATCH",
            "fatal",
            "structure",
            expectedInputRef = "batch-bp01-cmb-b3",
        )
    }

    @Test // P-22
    fun b4OffByOneHeaderTokenRejectsBatchFatal() {
        val result = parseFixture("batch-bp01-cmb-b4", "batch-bp01-cmb-b4.csv")
        assertEquals(CmbBatchOutcome.REJECTED, result.outcome)
        assertEquals(0, result.rows.size)
        assertDiagnostic(
            assertIs(result.diagnostic),
            "STRUCTURE_MISMATCH",
            "fatal",
            "structure",
            expectedInputRef = "batch-bp01-cmb-b4",
        )
    }

    @Test // P-23
    fun cCommentBlockVariantRejectsBatchFatal() {
        val result = parseFixture("batch-bp01-cmb-c", "batch-bp01-cmb-c.csv")
        assertEquals(CmbBatchOutcome.REJECTED, result.outcome)
        assertEquals(0, result.rows.size)
        assertDiagnostic(
            assertIs(result.diagnostic),
            "STRUCTURE_MISMATCH",
            "fatal",
            "structure",
            expectedInputRef = "batch-bp01-cmb-c",
        )
    }

    @Test // P-24
    fun dNoTrailingSummaryBlockIsCompleteWithZeroDiagnostics() {
        // Spec section 4.2: batch-bp01-cmb-d ends at the last data row (no tail block)
        // and carries the clean R1..R8 prefix, so the batch outcome is complete with
        // zero diagnostics — the tail block is provably optional.
        val result = parseFixture("batch-bp01-cmb-d", "batch-bp01-cmb-d.csv")
        assertEquals(CmbBatchOutcome.COMPLETE, result.outcome)
        assertNull(result.diagnostic)
        assertEquals(8, result.rows.size)
        result.rows.forEach { assertIs<CmbRowResult.Accepted>(it) }
        assertEquals(0, result.rows.flatMap { it.diagnostics }.size)
    }

    @Test // P-25
    fun e1SixFieldDataRowIsRecordLevelStructureMismatch() {
        val result = parseFixture("batch-bp01-cmb-e1", "batch-bp01-cmb-e1.csv")
        assertEquals(CmbBatchOutcome.PARTIAL, result.outcome)
        assertEquals(3, result.rows.size)
        assertIs<CmbRowResult.Accepted>(result.rows[0])
        val bad = assertIs<CmbRowResult.Rejected>(result.rows[1])
        assertDiagnostic(bad.diagnostics.single(), "STRUCTURE_MISMATCH", "fatal", "record", 1, expectedInputRef = "batch-bp01-cmb-e1")
        assertIs<CmbRowResult.Accepted>(result.rows[2])
    }

    @Test // P-26
    fun e2EightFieldDataRowIsRecordLevelStructureMismatch() {
        val result = parseFixture("batch-bp01-cmb-e2", "batch-bp01-cmb-e2.csv")
        assertEquals(CmbBatchOutcome.PARTIAL, result.outcome)
        assertEquals(3, result.rows.size)
        val bad = assertIs<CmbRowResult.Rejected>(result.rows[1])
        assertDiagnostic(bad.diagnostics.single(), "STRUCTURE_MISMATCH", "fatal", "record", 1, expectedInputRef = "batch-bp01-cmb-e2")
    }

    @Test // P-27
    fun e3TabInsideNonTabColumnIsRecordLevelStructureMismatch() {
        val result = parseFixture("batch-bp01-cmb-e3", "batch-bp01-cmb-e3.csv")
        assertEquals(CmbBatchOutcome.PARTIAL, result.outcome)
        assertEquals(3, result.rows.size)
        val bad = assertIs<CmbRowResult.Rejected>(result.rows[1])
        assertDiagnostic(bad.diagnostics.single(), "STRUCTURE_MISMATCH", "fatal", "record", 1, expectedInputRef = "batch-bp01-cmb-e3")
    }

    @Test // P-28
    fun fEmptyAndNonUtf8InputsRejectAsDecodeFailed() {
        val empty = CmbBillParser.parse("batch-bp01-cmb-f", fixtureBytes("batch-bp01-cmb-f-empty.csv"))
        assertEquals(CmbBatchOutcome.REJECTED, empty.outcome)
        assertEquals(0, empty.rows.size)
        assertDiagnostic(assertIs(empty.diagnostic), "INPUT_DECODE_FAILED", "fatal", "input", expectedInputRef = "batch-bp01-cmb-f")

        val nonUtf8 = CmbBillParser.parse("batch-bp01-cmb-f", fixtureBytes("batch-bp01-cmb-f-nonutf8.bin"))
        assertEquals(CmbBatchOutcome.REJECTED, nonUtf8.outcome)
        assertEquals(0, nonUtf8.rows.size)
        assertDiagnostic(assertIs(nonUtf8.diagnostic), "INPUT_DECODE_FAILED", "fatal", "input", expectedInputRef = "batch-bp01-cmb-f")
    }

    @Test // P-29
    fun gEmptyBalanceColumnIsNonBlockingNoteWithValidCompleteRecord() {
        val result = parseFixture("batch-bp01-cmb-g", "batch-bp01-cmb-g.csv")
        assertEquals(CmbBatchOutcome.PARTIAL, result.outcome)
        val g1 = accepted(result.rows, 0)
        assertEquals(settledFacts(12850, "2026-08-10T15:30:00+08:00", "out"), g1.facts)
        assertEquals(ImportCompleteness.VALID_COMPLETE, g1.completeness)
        assertEquals(1, g1.diagnostics.size)
        assertDiagnostic(g1.diagnostics[0], "SPINE_BANK_BALANCE_MISSING", "note", "field", 0, "balance", expectedInputRef = "batch-bp01-cmb-g")
    }

    @Test // P-30
    fun hExtensionBatchRoutesEveryRowAndKeepsBalanceChainConsistent() {
        val result = parseFixture("batch-bp01-cmb-h", "batch-bp01-cmb-h.csv")
        assertEquals(CmbBatchOutcome.PARTIAL, result.outcome)
        assertNull(result.diagnostic)
        assertEquals(19, result.rows.size)

        val expectedRoutes =
            listOf(
                0 to ImportRecordKind.ORDINARY_FLOW_SOURCE, // H1 网联付款交易 in 30.00 (E-07b row)
                1 to ImportRecordKind.TRANSFER_FLOW_SOURCE, // H2 数字人民币存银行 in 50.00 (E-07c row)
                2 to ImportRecordKind.ORDINARY_FLOW_SOURCE, // H3 汇入汇款
                3 to ImportRecordKind.ORDINARY_FLOW_SOURCE, // H4 汇入汇款
                4 to ImportRecordKind.ORDINARY_FLOW_SOURCE, // H5 支付鼓励金
                5 to ImportRecordKind.ORDINARY_FLOW_SOURCE, // H6 支付鼓励金
                6 to ImportRecordKind.ORDINARY_FLOW_SOURCE, // H7 银联快捷支付 in
                7 to ImportRecordKind.ORDINARY_FLOW_SOURCE, // H8 银联快捷支付 in
                8 to ImportRecordKind.ORDINARY_FLOW_SOURCE, // H9 账户结息
                9 to ImportRecordKind.ORDINARY_FLOW_SOURCE, // H10 银联在线支付
                10 to ImportRecordKind.ORDINARY_FLOW_SOURCE, // H11 银联在线支付
                11 to ImportRecordKind.ORDINARY_FLOW_SOURCE, // H12 数字人民币随用随充消费
                12 to ImportRecordKind.ORDINARY_FLOW_SOURCE, // H13 数字人民币随用随充消费
                13 to ImportRecordKind.ORDINARY_FLOW_SOURCE, // H14 银联快捷支付 out
                14 to ImportRecordKind.TRANSFER_FLOW_SOURCE, // H15 朝朝宝赎回
                15 to ImportRecordKind.TRANSFER_FLOW_SOURCE, // H16 数字人民币充值
                16 to ImportRecordKind.TRANSFER_FLOW_SOURCE, // H17 朝朝宝购买
                17 to ImportRecordKind.ORDINARY_FLOW_SOURCE, // H18 网联付款交易
            )
        expectedRoutes.forEach { (ordinal, kind) ->
            val row = accepted(result.rows, ordinal)
            assertEquals(kind, row.recordKind, "H${ordinal + 1} route")
            assertEquals(ImportCompleteness.VALID_COMPLETE, row.completeness)
            assertEquals(emptyList(), row.diagnostics)
        }
        val z = rejected(result.rows, 18)
        assertDiagnostic(z.diagnostics.single(), "SPINE_CMB_UNKNOWN_TOKEN", "unsupported", "record", 18, expectedInputRef = "batch-bp01-cmb-h")

        val acceptedRows = result.rows.filterIsInstance<CmbRowResult.Accepted>()
        assertEquals(18, acceptedRows.size)
        assertEquals(1, result.rows.filterIsInstance<CmbRowResult.Rejected>().size)

        // Whole-batch diagnostic multiset is pinned to the single ZDFF rejection: the
        // 19-row self-standing chain (anchor 3000.00, descending, no mismatch, rejected
        // row still participating) produces zero balance-continuity notes (spec 4.2 h).
        val hMultiset = result.rows.flatMap { row -> row.diagnostics.map { Pair(it.code, it.recordOrdinal) } }
        assertEquals(listOf(Pair("SPINE_CMB_UNKNOWN_TOKEN", 18)), hMultiset)

        // E-07b/E-07c anchor rows (spec 4.2 h consistency statement): H1 网联付款交易
        // in 30.00 with the self-wallet channel remark marker, H2 数字人民币存银行 in 50.00.
        val h1 = accepted(result.rows, 0)
        assertEquals(ImportRecordKind.ORDINARY_FLOW_SOURCE, h1.recordKind)
        assertEquals(settledFacts(3000, "2026-08-08T09:00:00+08:00", "in"), h1.facts)
        val h2 = accepted(result.rows, 1)
        assertEquals(ImportRecordKind.TRANSFER_FLOW_SOURCE, h2.recordKind)
        assertEquals(settledFacts(5000, "2026-08-08T14:30:00+08:00", "in"), h2.facts)

        // Frozen descending chain over all raw rows (rejected row included): the parser
        // exposes the chain only through its continuity verdicts, so zero continuity
        // notes over the whole batch is the chain assertion — any mismatch anywhere in
        // the 19-row chain (anchor 3000.00) would add a SPINE_BANK_BALANCE_CONTINUITY
        // note and break the pinned multiset above.
        assertTrue(result.rows.flatMap { it.diagnostics }.none { it.code == "SPINE_BANK_BALANCE_CONTINUITY" })
    }

    // ---- P-54: CMB descending balance-chain vector ----

    @Test // P-54 (CMB)
    fun descendingBalanceChainHoldsAcrossDateBoundariesAndSameDayRows() {
        val rows =
            listOf(
                vecRow("20240301", "10:00:00", "", "10.00", "100.00"),
                vecRow("20240229", "23:59:00", "", "5.00", "110.00"),
                vecRow("20240229", "00:01:00", "", "2.00", "115.00"),
                vecRow("20240131", "12:00:00", "7.00", "", "117.00"),
            )
        val result = CmbBillParser.parse("batch-bp01-cmb-p54", vectorCsv(rows))
        assertEquals(CmbBatchOutcome.COMPLETE, result.outcome)
        assertNull(result.diagnostic)
        assertEquals(4, result.rows.size)
        result.rows.forEach { row ->
            assertIs<CmbRowResult.Accepted>(row)
            assertEquals(emptyList(), row.diagnostics)
        }
        assertEquals("2024-02-29T23:59:00+08:00", accepted(result.rows, 1).facts.occurredAt)
        assertEquals("2024-01-31T12:00:00+08:00", accepted(result.rows, 3).facts.occurredAt)
    }

    // ---- P-55: CMB privacy disjointness ----

    @Test // P-55 (CMB)
    fun commentBlockAndRemarkValuesNeverLeakIntoOutputs() {
        val result = parseFixture(inputRef, "batch-bp01-cmb-a.csv")
        val outputs = outputStrings(result)
        val secrets =
            listOf(
                "SYN-CMB-ACCOUNT-1234",
                "招商银行交易明细",
                "SYN-CMB-REMARK-R1",
                "SYN-CMB-REMARK-R17",
                "# 收入合计: 100000836.29",
                "# 支出合计: 785.40",
            )
        secrets.forEach { secret ->
            assertTrue(outputs.none { it == secret || it.contains(secret) }, "metadata/remark value leaked: $secret")
        }
    }

    private fun vectorCsv(rows: List<String>): ByteArray {
        val comment = (0..5).map { "\"SYN-CMB-META-$it\"" }
        val lines =
            comment +
                listOf("\"\"") +
                listOf("\"交易日期\",\"交易时间\",\"收入\",\"支出\",\"余额\",\"交易类型\",\"交易备注\"") +
                rows +
                listOf("\"\"")
        return ("\uFEFF" + lines.joinToString("\r\n") + "\r\n").toByteArray(Charsets.UTF_8)
    }

    // RFC-4180 data row: every field individually quoted, ',' between quoted fields.
    private fun vecRow(
        date: String,
        time: String,
        income: String,
        expense: String,
        balance: String,
    ): String =
        listOf("\t$date", "\t$time", income, expense, balance, "网联协议支付", "\tSYN-CMB-VEC-REMARK")
            .joinToString(",") { "\"$it\"" }
}
