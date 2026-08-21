package com.unifiedledger.application.import.alipay

import com.unifiedledger.application.IMPORT_FUNDING_RULE_LEGACY_SETTLED
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportFundingState
import com.unifiedledger.application.ImportRecordKind
import com.unifiedledger.application.ImportSourceFacts
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RL-04 (P4-05b) 余额宝 transfer routing parser oracle (frozen spec
 * docs/specs/2026-08-18-p4-05b-rl04-yuebao-transfer-routing-design.md, sections 1.2/1.3/6:
 * T-01..T-18, T-21, T-22 mapping P-01..P-18 plus the status-gate and privacy vectors;
 * Y-01..Y-15 fixtures, batch-rl04-a).
 *
 * All CSV inputs are synthetic and provider-neutral (GB18030 primary path), same frozen
 * file shape as P4-05 (§2.1 reuse, zero revision): metadata area = 23 CRLF lines
 * (0-based 0..22), header at 0-based line 23, data rows from line 24, record_ordinal =
 * row - 24. The 投资理财 分支 routes 余额宝-* subtypes to TRANSFER_FLOW_SOURCE; every other
 * route is byte-for-byte the P4-05 oracle (proven by the untouched AlipayCsvParserJvmTest
 * suite remaining green plus the mixed-batch assertion in P-18/R-01).
 */
class AlipayCsvParserYuebaoTransferJvmTest {

    private val inputRef = "batch-rl04-a"
    private val gb18030: Charset = Charset.forName("GB18030")

    // ---- Synthetic CSV builder (same frozen shape as P4-05 §2.1) ----

    private fun metadataLines(): List<String> =
        (0..22).map { "SYN-META-PII-EXPORT-$it,SYN-META-PII-NICK-$it" }

    private fun headerLine(): String = AlipaySourceTokens.HEADER_TOKENS.joinToString(",") + ","

    /** Frozen-shape data row in the REAL column layout (§9.2 of P4-05, reused as-is):
     *  time[0], category[1], counterparty[2], account[3], 商品说明[4], 收/支[5], amount[6],
     *  收/付款方式[7], status[8], order[9] (trailing tab), merchant[10] (trailing tab or empty),
     *  note[11]. [method] = null renders the empty method shape. */
    private fun yuebaoRow(
        subtype: String,
        directionCol: String,
        amount: String,
        status: String,
        time: String,
        method: String?,
        merchOrderNo: String? = "SYN-SECRET-MERCHNO",
    ): String = listOf(
        time, "投资理财", "SYN-SECRET-COUNTERPARTY", "SYN-SECRET-ACCOUNT",
        subtype, directionCol, amount, method ?: "", status,
        "SYN-SECRET-TXNO\t", merchOrderNo?.let { "$it\t" } ?: "", "SYN-SECRET-NOTE",
    ).joinToString(",") + ","

    /**
     * Frozen source record rows Y-01..Y-15 (design section 1.2), batch-rl04-a data area.
     * Y-01..Y-07 自动转入 (method 空×3 + 账户余额×4), Y-08 单次转入/交易关闭, Y-09 转出到余额;
     * Y-10..Y-15 synthetic defensive rows (explicitly marked; mask method shape).
     */
    private fun batchARows(): List<String> = listOf(
        yuebaoRow("余额宝-自动转入", "不计收支", "100.00", "交易成功", "2026-08-01 12:30:45", null),                       // Y-01
        yuebaoRow("余额宝-自动转入", "不计收支", "200.00", "交易成功", "2026-08-01 13:00:00", "账户余额"),                // Y-02
        yuebaoRow("余额宝-自动转入", "不计收支", "300.00", "交易成功", "2026-08-02 09:15:30", "账户余额"),                // Y-03
        yuebaoRow("余额宝-自动转入", "不计收支", "400.00", "交易成功", "2026-08-02 10:20:00", null),                       // Y-04
        yuebaoRow("余额宝-自动转入", "不计收支", "500.00", "交易成功", "2026-08-03 11:05:45", "账户余额"),                // Y-05
        yuebaoRow("余额宝-自动转入", "不计收支", "600.00", "交易成功", "2026-08-03 14:10:20", "账户余额"),                // Y-06
        yuebaoRow("余额宝-自动转入", "不计收支", "700.00", "交易成功", "2026-08-04 08:25:15", null),                       // Y-07
        yuebaoRow("余额宝-单次转入", "不计收支", "80.00", "交易关闭", "2026-08-05 09:00:00", null),                        // Y-08
        yuebaoRow("余额宝-转出到余额", "不计收支", "900.00", "交易成功", "2026-08-06 16:40:35", "余额"),                  // Y-09
        yuebaoRow("余额宝-转出到银行卡", "不计收支", "75.50", "交易成功", "2026-08-07 10:00:00", "SYN-MASK-METHOD"),      // Y-10
        yuebaoRow("余额宝-收益发放", "不计收支", "12.34", "交易成功", "2026-08-07 10:30:00", "SYN-MASK-METHOD"),          // Y-11
        yuebaoRow("余额宝-自动转入", "不计收支", "111.11", "交易关闭", "2026-08-08 09:00:00", null),                       // Y-12
        yuebaoRow("基金买入", "不计收支", "99.99", "交易成功", "2026-08-08 10:00:00", null),                                // Y-13
        yuebaoRow("余额宝-自动转入", "支出", "123.45", "交易成功", "2026-08-09 11:11:11", null),                            // Y-14
        yuebaoRow("余额宝-自动转入", "不计收支", "456.78", "退款成功", "2026-08-10 12:12:12", null),                        // Y-15
    )

    private fun csvBytes(dataRows: List<String>): ByteArray = buildString {
        metadataLines().forEach { append(it).append("\r\n") }
        append(headerLine()).append("\n")
        dataRows.forEach { append(it).append("\n") }
    }.toByteArray(gb18030)

    private fun accepted(rows: List<AlipayRowResult>, ordinal: Int): AlipayRowResult.Accepted {
        val row = rows.first { it.recordOrdinal == ordinal }
        return assertIs<AlipayRowResult.Accepted>(row)
    }

    private fun rejected(rows: List<AlipayRowResult>, ordinal: Int): AlipayRowResult.Rejected {
        val row = rows.first { it.recordOrdinal == ordinal }
        return assertIs<AlipayRowResult.Rejected>(row)
    }

    private fun assertDiagnostic(
        diagnostic: AlipayDiagnostic,
        code: String,
        severity: String,
        scope: String,
        ordinal: Int? = null,
        fieldRole: String? = null,
    ) {
        assertEquals(code, diagnostic.code)
        assertEquals(severity, diagnostic.severity)
        assertEquals(scope, diagnostic.scope)
        assertEquals(inputRef, diagnostic.inputRef)
        assertEquals(ordinal, diagnostic.recordOrdinal)
        assertEquals(fieldRole, diagnostic.fieldRole)
    }

    private fun outputStrings(result: AlipayBatchResult): List<String> = result.rows.flatMap { row ->
        when (row) {
            is AlipayRowResult.Accepted -> listOf(
                row.facts.amountMinor.toString(), row.facts.currencyCode, row.facts.currencyPrecision.toString(),
                row.facts.occurredAt, row.facts.directionToken, row.facts.statusToken ?: "",
            )
            is AlipayRowResult.Rejected -> emptyList()
        } + row.diagnostics.flatMap {
            listOf(it.code, it.severity, it.scope, it.inputRef, it.recordOrdinal?.toString() ?: "", it.fieldRole ?: "")
        }
    }

    /** Frozen transfer-facts shape helper: subtype-derived direction + status + transfer kind. */
    private fun transferFacts(
        amountMinor: Long,
        direction: String,
        occurredAt: String,
        status: String?,
    ) = ImportSourceFacts(amountMinor, "CNY", 2, occurredAt, direction, status, ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)

    // ---- T-01..T-07 / P-01..P-07: 余额宝-自动转入 (7 real-shaped rows) ----

    @Test // T-01 / P-01
    fun y01AutoTransferUntilBalancesRoutesToTransferFlowWithDerivedDirection() {
        val result = AlipayCsvParser.parse(inputRef, csvBytes(batchARows()))
        val y01 = accepted(result.rows, 0)
        assertEquals(ImportRecordKind.TRANSFER_FLOW_SOURCE, y01.recordKind)
        assertEquals(ImportRecordKind.TRANSFER_FLOW_SOURCE.contractVersion, 2)
        assertEquals(
            transferFacts(10000, "out", "2026-08-01T12:30:45+08:00", "settled"),
            y01.facts,
        )
        assertEquals(ImportCompleteness.VALID_COMPLETE, y01.completeness)
        assertEquals(emptyList(), y01.diagnostics)
        // Frozen subtype-direction provenance (design §2.2): exact tokens + rule constant.
        assertEquals(setOf("余额宝-自动转入", "余额宝-转出到余额"), AlipaySourceTokens.YUEBAO_TRANSFER_SUBTYPES)
        assertEquals("out", AlipaySourceTokens.YUEBAO_SUBTYPE_DIRECTION_MAP["余额宝-自动转入"])
        assertEquals("in", AlipaySourceTokens.YUEBAO_SUBTYPE_DIRECTION_MAP["余额宝-转出到余额"])
        assertEquals("yuebao_subtype_direction_v1", AlipaySourceTokens.YUEBAO_SUBTYPE_DIRECTION_RULE)
    }

    @Test // T-02..T-07 / P-02..P-07
    fun y02ToY07AutoTransferMethodShapesNeverAffectRouting() {
        val result = AlipayCsvParser.parse(inputRef, csvBytes(batchARows()))
        val expected = listOf(
            transferFacts(20000, "out", "2026-08-01T13:00:00+08:00", "settled"),
            transferFacts(30000, "out", "2026-08-02T09:15:30+08:00", "settled"),
            transferFacts(40000, "out", "2026-08-02T10:20:00+08:00", "settled"),
            transferFacts(50000, "out", "2026-08-03T11:05:45+08:00", "settled"),
            transferFacts(60000, "out", "2026-08-03T14:10:20+08:00", "settled"),
            transferFacts(70000, "out", "2026-08-04T08:25:15+08:00", "settled"),
        )
        (1..6).forEach { index ->
            val row = accepted(result.rows, index)
            assertEquals(ImportRecordKind.TRANSFER_FLOW_SOURCE, row.recordKind, "Y-0${index + 1} kind")
            assertEquals(expected[index - 1], row.facts, "Y-0${index + 1} facts")
            assertEquals(ImportCompleteness.VALID_COMPLETE, row.completeness, "Y-0${index + 1} completeness")
            assertEquals(emptyList(), row.diagnostics, "Y-0${index + 1} diagnostics")
        }
    }

    // ---- T-08 / P-08: 余额宝-单次转入 (only sample is 交易关闭; not frozen) ----

    @Test // T-08 / P-08
    fun y08SingleTransferClosedRejectedAsUnknownTokenZeroRecord() {
        val result = AlipayCsvParser.parse(inputRef, csvBytes(batchARows()))
        val y08 = rejected(result.rows, 7)
        assertDiagnostic(y08.diagnostics.single(), "SPINE_ALIPAY_UNKNOWN_TOKEN", "unsupported", "record", 7)
        assertTrue(AlipaySourceTokens.YUEBAO_TRANSFER_SUBTYPES.none { it == "余额宝-单次转入" })
    }

    // ---- T-09 / P-09: 余额宝-转出到余额 (wallet TO leg, direction in) ----

    @Test // T-09 / P-09
    fun y09TransferBackToBalanceRoutesWithInDirection() {
        val result = AlipayCsvParser.parse(inputRef, csvBytes(batchARows()))
        val y09 = accepted(result.rows, 8)
        assertEquals(ImportRecordKind.TRANSFER_FLOW_SOURCE, y09.recordKind)
        assertEquals(
            transferFacts(90000, "in", "2026-08-06T16:40:35+08:00", "settled"),
            y09.facts,
        )
        assertEquals(ImportCompleteness.VALID_COMPLETE, y09.completeness)
        assertEquals(emptyList(), y09.diagnostics)
    }

    // ---- T-10..T-11 / P-10..P-11: fail-closed non-frozen registered families ----

    @Test // T-10 / P-10
    fun y10TransferToBankCardRejectedAsUnknownTokenMissingLegRegistration() {
        val result = AlipayCsvParser.parse(inputRef, csvBytes(batchARows()))
        val y10 = rejected(result.rows, 9)
        assertDiagnostic(y10.diagnostics.single(), "SPINE_ALIPAY_UNKNOWN_TOKEN", "unsupported", "record", 9)
        assertTrue(AlipaySourceTokens.YUEBAO_TRANSFER_SUBTYPES.none { it == "余额宝-转出到银行卡" })
    }

    @Test // T-11 / P-11
    fun y11IncomeDistributionRejectedAsUnknownTokenHardIncomeRegistration() {
        val result = AlipayCsvParser.parse(inputRef, csvBytes(batchARows()))
        val y11 = rejected(result.rows, 10)
        assertDiagnostic(y11.diagnostics.single(), "SPINE_ALIPAY_UNKNOWN_TOKEN", "unsupported", "record", 10)
        assertTrue(AlipaySourceTokens.YUEBAO_TRANSFER_SUBTYPES.none { it == "余额宝-收益发放" })
    }

    // ---- T-12 / P-12: frozen subtype + non-success status -> valid_incomplete ----

    @Test // T-12 / P-12
    fun y12AutoTransferClosedStatusStaysRawAndValidIncomplete() {
        val result = AlipayCsvParser.parse(inputRef, csvBytes(batchARows()))
        val y12 = accepted(result.rows, 11)
        assertEquals(ImportRecordKind.TRANSFER_FLOW_SOURCE, y12.recordKind)
        assertEquals(
            transferFacts(11111, "out", "2026-08-08T09:00:00+08:00", "交易关闭"),
            y12.facts,
        )
        assertEquals(ImportCompleteness.VALID_INCOMPLETE, y12.completeness)
        assertEquals(1, y12.diagnostics.size)
        assertDiagnostic(y12.diagnostics[0], "REQUIRED_FACT_UNRESOLVED", "incomplete", "field", 11, "status")
    }

    // ---- T-13 / P-13: unknown non-余额宝 token -> fail-closed ----

    @Test // T-13 / P-13
    fun y13UnknownSubtypeTokenRejectedAsUnknownToken() {
        val result = AlipayCsvParser.parse(inputRef, csvBytes(batchARows()))
        val y13 = rejected(result.rows, 12)
        assertDiagnostic(y13.diagnostics.single(), "SPINE_ALIPAY_UNKNOWN_TOKEN", "unsupported", "record", 12)
    }

    // ---- T-14 / P-14: 收/支 column never participates in direction judgement ----

    @Test // T-14 / P-14
    fun y14DirectionColumnIgnoredDirectionStillSubtypeDerivedOut() {
        val result = AlipayCsvParser.parse(inputRef, csvBytes(batchARows()))
        val y14 = accepted(result.rows, 13)
        assertEquals(ImportRecordKind.TRANSFER_FLOW_SOURCE, y14.recordKind)
        assertEquals(
            transferFacts(12345, "out", "2026-08-09T11:11:11+08:00", "settled"),
            y14.facts,
        )
        assertEquals(ImportCompleteness.VALID_COMPLETE, y14.completeness)
        assertEquals(emptyList(), y14.diagnostics)
    }

    // ---- T-15 / P-15: refund marker wins judgment order 1 over the routing branch ----

    @Test // T-15 / P-15
    fun y15RefundStatusRejectedByJudgmentOrderOneBeforeRoutingBranch() {
        val result = AlipayCsvParser.parse(inputRef, csvBytes(batchARows()))
        val y15 = rejected(result.rows, 14)
        assertDiagnostic(y15.diagnostics.single(), "SPINE_ALIPAY_REFUND_UNSUPPORTED", "unsupported", "record", 14)
    }

    // ---- T-16 / P-16: whole batch ----

    @Test // T-16 / P-16
    fun wholeBatchIsPartialWithTenRecordsAndSixDiagnostics() {
        val result = AlipayCsvParser.parse(inputRef, csvBytes(batchARows()))
        assertEquals(AlipayBatchOutcome.PARTIAL, result.outcome)
        assertNull(result.diagnostic)

        assertEquals(15, result.rows.size)
        val acceptedRows = result.rows.filterIsInstance<AlipayRowResult.Accepted>()
        assertEquals(10, acceptedRows.size)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 8, 11, 13), acceptedRows.map { it.recordOrdinal })
        assertEquals(9, acceptedRows.count { it.completeness == ImportCompleteness.VALID_COMPLETE })
        assertEquals(1, acceptedRows.count { it.completeness == ImportCompleteness.VALID_INCOMPLETE })
        assertEquals(5, result.rows.count { it is AlipayRowResult.Rejected })
        result.rows.filterIsInstance<AlipayRowResult.Rejected>().forEach {
            assertEquals(1, it.diagnostics.size, "rejected row carries exactly one diagnostic")
        }

        // Frozen 6-entry diagnostic multiset (design section 1.3 P-16; message never compared).
        val multiset = result.rows
            .flatMap { row -> row.diagnostics.map { Triple(it.code, it.recordOrdinal, it.fieldRole) } }
            .sortedWith(compareBy({ it.second ?: -1 }, { it.first }))
        assertEquals(
            listOf(
                Triple("SPINE_ALIPAY_UNKNOWN_TOKEN", 7, null),
                Triple("SPINE_ALIPAY_UNKNOWN_TOKEN", 9, null),
                Triple("SPINE_ALIPAY_UNKNOWN_TOKEN", 10, null),
                Triple("REQUIRED_FACT_UNRESOLVED", 11, "status"),
                Triple("SPINE_ALIPAY_UNKNOWN_TOKEN", 12, null),
                Triple("SPINE_ALIPAY_REFUND_UNSUPPORTED", 14, null),
            ),
            multiset,
        )
    }

    // ---- T-17 / P-17: privacy - 商品说明 / 收/付款方式 / 收/支 disjoint from all output ----

    @Test // T-17 / P-17
    fun nonRoutedColumnValuesNeverLeakIntoOutputOrDiagnostics() {
        val result = AlipayCsvParser.parse(inputRef, csvBytes(batchARows()))
        val outputs = outputStrings(result)
        val columnValueSecrets = listOf(
            "余额宝-自动转入", "余额宝-单次转入", "余额宝-转出到余额", "余额宝-转出到银行卡",
            "余额宝-收益发放", "基金买入", "账户余额", "余额", "SYN-MASK-METHOD", "不计收支", "支出",
        )
        columnValueSecrets.forEach { secret ->
            assertTrue(
                outputs.none { it.isNotEmpty() && it.contains(secret) },
                "non-routed column value leaked: $secret",
            )
        }
        // The empty method shape (空) carries no token content of its own: the absence of the
        // content-bearing method/收支/subtype values above is the full privacy contract. A bare
        // empty string is also used as the null-fieldRole placeholder in the diagnostic
        // flattening, so no empty-string leak assertion is applicable here.
    }

    // ---- T-18 / P-18 + R-01: judgment-order defense and ordinary-route invariance ----

    @Test // T-18 / P-18 / R-01
    fun judgmentOrderDefenseAndOrdinaryRowsRouteUnchangedInMixedBatch() {
        // Refund priority subset re-parsed alone (defense vector, Y-15).
        val refundOnly = AlipayCsvParser.parse(inputRef, csvBytes(listOf(batchARows()[14])))
        assertEquals(
            "SPINE_ALIPAY_REFUND_UNSUPPORTED",
            rejected(refundOnly.rows, 0).diagnostics.single().code,
        )

        // Mixed batch: P4-05 ordinary rows (A-01/A-02 shape) plus the yuebao batch. The
        // ordinary rows must keep the exact P4-05 facts/diagnostics; the yuebao branch must
        // not disturb them (R-01 zero oracle shift).
        val mixed = AlipayCsvParser.parse(
            inputRef,
            csvBytes(
                listOf(
                    listOf(
                        "2026-08-01 12:30:45", "网上支付", "SYN-SECRET-COUNTERPARTY", "SYN-SECRET-ACCOUNT",
                        "SYN-SECRET-PRODUCT", "支出", "128.50", "SYN-SECRET-METHOD", "交易成功",
                        "SYN-SECRET-TXNO\t", "SYN-SECRET-MERCHNO\t", "SYN-SECRET-NOTE",
                    ).joinToString(",") + ",",
                ) + batchARows(),
            ),
        )
        val ordinary = accepted(mixed.rows, 0)
        assertEquals(ImportRecordKind.ORDINARY_FLOW_SOURCE, ordinary.recordKind)
        assertEquals(ImportSourceFacts(12850, "CNY", 2, "2026-08-01T12:30:45+08:00", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1), ordinary.facts)
        assertEquals(ImportCompleteness.VALID_COMPLETE, ordinary.completeness)
        assertEquals(emptyList(), ordinary.diagnostics)
        assertEquals(
            10,
            mixed.rows.filterIsInstance<AlipayRowResult.Accepted>().count { it.recordKind == ImportRecordKind.TRANSFER_FLOW_SOURCE },
        )
    }

    // ---- T-21: status-gate vector - 余额宝-转出到余额 + 交易关闭 ----

    @Test // T-21
    fun transferBackToBalanceWithClosedStatusStaysValidIncomplete() {
        val rows = listOf(
            yuebaoRow("余额宝-转出到余额", "不计收支", "55.55", "交易关闭", "2026-08-11 09:00:00", "余额"),
        )
        val result = AlipayCsvParser.parse(inputRef, csvBytes(rows))
        assertEquals(AlipayBatchOutcome.PARTIAL, result.outcome)
        val row = accepted(result.rows, 0)
        assertEquals(ImportRecordKind.TRANSFER_FLOW_SOURCE, row.recordKind)
        assertEquals(
            transferFacts(5555, "in", "2026-08-11T09:00:00+08:00", "交易关闭"),
            row.facts,
        )
        assertEquals(ImportCompleteness.VALID_INCOMPLETE, row.completeness)
        assertEquals(1, row.diagnostics.size)
        assertDiagnostic(row.diagnostics[0], "REQUIRED_FACT_UNRESOLVED", "incomplete", "field", 0, "status")
    }

    // ---- T-22: 收/付款方式 tokens (账户余额/余额/空) are behavior evidence only ----

    @Test // T-22
    fun methodColumnTokensAreBehaviorEvidenceOnlyAndNeverPersisted() {
        val rows = listOf(
            yuebaoRow("余额宝-自动转入", "不计收支", "10.00", "交易成功", "2026-08-12 08:00:00", "账户余额"),
            yuebaoRow("余额宝-转出到余额", "不计收支", "20.00", "交易成功", "2026-08-12 08:30:00", "余额"),
            yuebaoRow("余额宝-自动转入", "不计收支", "30.00", "交易成功", "2026-08-12 09:00:00", null),
        )
        val result = AlipayCsvParser.parse(inputRef, csvBytes(rows))
        assertEquals(AlipayBatchOutcome.COMPLETE, result.outcome)
        val outputs = outputStrings(result)
        listOf("账户余额", "余额").forEach { token ->
            assertTrue(outputs.none { it.contains(token) }, "method token leaked: $token")
        }
        // The routed facts never contain the 收/支 neutral token either.
        assertTrue(outputs.none { it.contains("不计收支") })
    }

    // ---- Defensive edge: frozen subtype + malformed date/amount fields (zero record) ----

    @Test // defensive: frozen subtype + malformed amount shape -> FIELD_AMOUNT_INVALID
    fun yuebaoFrozenSubtypeWithMalformedAmountRejectedAsFieldAmountInvalid() {
        val rows = listOf(yuebaoRow("余额宝-自动转入", "不计收支", "12.5", "交易成功", "2026-08-13 09:00:00", null))
        val result = AlipayCsvParser.parse(inputRef, csvBytes(rows))
        assertEquals(AlipayBatchOutcome.PARTIAL, result.outcome)
        assertEquals(0, result.rows.count { it is AlipayRowResult.Accepted })
        val row = rejected(result.rows, 0)
        assertEquals(1, row.diagnostics.size)
        assertDiagnostic(
            row.diagnostics[0], "FIELD_AMOUNT_INVALID", "record_error", "field", 0,
            AlipaySourceTokens.FIELD_ROLE_AMOUNT,
        )
    }

    @Test // defensive: frozen subtype + malformed time shape -> FIELD_TIME_INVALID
    fun yuebaoFrozenSubtypeWithMalformedTimeRejectedAsFieldTimeInvalid() {
        val rows = listOf(yuebaoRow("余额宝-转出到余额", "不计收支", "20.00", "交易成功", "not-a-time", "余额"))
        val result = AlipayCsvParser.parse(inputRef, csvBytes(rows))
        assertEquals(AlipayBatchOutcome.PARTIAL, result.outcome)
        assertEquals(0, result.rows.count { it is AlipayRowResult.Accepted })
        val row = rejected(result.rows, 0)
        assertEquals(1, row.diagnostics.size)
        assertDiagnostic(
            row.diagnostics[0], "FIELD_TIME_INVALID", "record_error", "field", 0,
            AlipaySourceTokens.FIELD_ROLE_OCCURRED_AT,
        )
    }
}
