package com.unifiedledger.application.import.alipay

import com.unifiedledger.application.IMPORT_FUNDING_RULE_LEGACY_SETTLED
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportFundingState
import com.unifiedledger.application.ImportPaymentProfile
import com.unifiedledger.application.ImportPaymentVariant
import com.unifiedledger.application.ImportRecordKind
import com.unifiedledger.application.ImportSourceFacts
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P4-05 parser oracle (frozen spec docs/specs/2026-08-17-p4-05-alipay-ordinary-flows-design.md,
 * sections 1.2/1.3/2/3/6: T-01..T-26 mapping P-01..P-23 plus the amount-shape, token-policy and
 * provider-DTO-zero vectors).
 *
 * All CSV inputs are synthetic and provider-neutral, generated in-test as GB18030 (primary path)
 * or UTF-8 (variant tolerance) bytes; no real files, no personal data. Frozen file shape:
 * metadata area = 23 CRLF lines (0-based lines 0..22, zero-read), header at 0-based line 23 =
 * 12 columns plus a trailing comma (13 fields), data rows from line 24 with LF endings, and
 * record_ordinal = row - 24. Data rows carry exactly two tabs (fields 9/10 trailing tabs) or
 * one tab when the merchant order number is empty.
 */
class AlipayCsvParserJvmTest {
    private val inputRef = "batch-p405-a"
    private val gb18030: Charset = Charset.forName("GB18030")

    private val a01Facts = ImportSourceFacts(12850, "CNY", 2, "2026-08-01T12:30:45+08:00", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)
    private val a02Facts = ImportSourceFacts(1250, "CNY", 2, "2026-08-05T09:00:00+08:00", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)
    private val a03Facts = ImportSourceFacts(8800, "CNY", 2, "2026-08-06T18:45:15+08:00", "in", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)

    // ---- Synthetic CSV builder (spec sections 1.1, 2.1-2.3) ----

    private fun metadataLines(): List<String> = (0..22).map { "SYN-META-PII-EXPORT-$it,SYN-META-PII-NICK-$it" }

    private fun headerLine(tokens: List<String> = AlipaySourceTokens.HEADER_TOKENS): String = tokens.joinToString(",") + ","

    private fun rawRow(
        fields: List<String>,
        trailingComma: Boolean = true,
    ): String = fields.joinToString(",") + if (trailingComma) "," else ""

    /** Generic frozen-shape data row in the REAL column layout (spec §9.2): time at index 0,
     *  category[1], counterparty[2], account[3], product 商品说明[4], direction[5], amount[6],
     *  method[7], status[8], order[9] (trailing tab), merchant order[10] (trailing tab or empty),
     *  note[11]. [merchOrderNo] = null renders the single-tab (empty) shape. Column 7 defaults
     *  to the empty string (P406S1-SPEC-001 data-fill revision: no payment legs, so the P4-06
     *  leg gate never triggers for these P4-05 fixtures). */
    private fun recordRow(
        category: String,
        direction: String,
        amount: String,
        status: String,
        time: String,
        merchOrderNo: String? = "SYN-SECRET-MERCHNO",
    ): String =
        rawRow(
            listOf(
                time,
                category,
                "SYN-SECRET-COUNTERPARTY",
                "SYN-SECRET-ACCOUNT",
                "SYN-SECRET-PRODUCT",
                direction,
                amount,
                "",
                status,
                "SYN-SECRET-TXNO\t",
                merchOrderNo?.let { "$it\t" } ?: "",
                "SYN-SECRET-NOTE",
            ),
        )

    private fun a01Fields(
        txOrderField: String = "SYN-SECRET-TXNO\t",
        merchOrderField: String = "SYN-SECRET-MERCHNO\t",
        note: String = "SYN-SECRET-NOTE",
    ): List<String> =
        listOf(
            "2026-08-01 12:30:45",
            "网上支付",
            "SYN-SECRET-COUNTERPARTY",
            "SYN-SECRET-ACCOUNT",
            "SYN-SECRET-PRODUCT",
            "支出",
            "128.50",
            "",
            "交易成功",
            txOrderField,
            merchOrderField,
            note,
        )

    private fun a01Row(
        txOrderField: String = "SYN-SECRET-TXNO\t",
        merchOrderField: String = "SYN-SECRET-MERCHNO\t",
        note: String = "SYN-SECRET-NOTE",
    ): String = rawRow(a01Fields(txOrderField, merchOrderField, note))

    private fun csvText(
        dataRows: List<String>,
        header: String = headerLine(),
    ): String =
        buildString {
            metadataLines().forEach { append(it).append("\r\n") }
            append(header).append("\n")
            dataRows.forEach { append(it).append("\n") }
        }

    private fun csvBytes(
        dataRows: List<String>,
        header: String = headerLine(),
        charset: Charset = gb18030,
    ): ByteArray = csvText(dataRows, header).toByteArray(charset)

    /** Frozen source record rows A-01..A-16 (spec section 1.2), batch-p405-a data area. */
    private fun batchARows(): List<String> =
        listOf(
            recordRow("网上支付", "支出", "128.50", "交易成功", "2026-08-01 12:30:45"),
            recordRow("扫码支付", "支出", "12.50", "交易成功", "2026-08-05 09:00:00"),
            recordRow("其他", "收入", "88.00", "交易成功", "2026-08-06 18:45:15", merchOrderNo = null),
            recordRow("网上支付", "不计收支", "45.60", "交易成功", "2026-08-09 21:15:30"),
            recordRow("网上支付", "支出", "20.00", "交易关闭", "2026-08-10 09:30:00"),
            recordRow("其他", "支出", "0.00", "交易成功", "2026-08-10 08:00:20"),
            recordRow("账户存取", "不计收支", "100.00", "交易成功", "2026-08-10 10:00:00"),
            recordRow("转账红包", "收入", "8.80", "交易成功", "2026-08-11 09:09:09"),
            recordRow("网上支付", "不计收支", "128.50", "退款成功", "2026-08-11 11:00:00"),
            recordRow("信用借还", "不计收支", "500.00", "还款", "2026-08-11 12:00:00"),
            recordRow("亲友代付", "支出", "66.00", "代付成功", "2026-08-11 13:00:00"),
            recordRow("神秘交易分类", "支出", "9.90", "交易成功", "2026-08-12 08:45:00"),
            recordRow("网上支付", "支出", "abc", "交易成功", "2026-08-12 07:30:00"),
            recordRow("网上支付", "支出", "10.00", "交易成功", "不是时间"),
            recordRow("网上支付", "支出", "-10.00", "交易成功", "2026-08-12 09:15:00"),
            recordRow("网上支付", "支出", "10.5", "交易成功", "2026-08-12 09:20:00"),
        )

    private fun minimalZipBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("synthetic.txt"))
            zos.write("SYN-CONTAINER-PAYLOAD".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return out.toByteArray()
    }

    private fun accepted(
        rows: List<AlipayRowResult>,
        ordinal: Int,
    ): AlipayRowResult.Accepted {
        val row = rows.first { it.recordOrdinal == ordinal }
        return assertIs<AlipayRowResult.Accepted>(row)
    }

    private fun rejected(
        rows: List<AlipayRowResult>,
        ordinal: Int,
    ): AlipayRowResult.Rejected {
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
        expectedInputRef: String = inputRef,
    ) {
        assertEquals(code, diagnostic.code)
        assertEquals(severity, diagnostic.severity)
        assertEquals(scope, diagnostic.scope)
        assertEquals(expectedInputRef, diagnostic.inputRef)
        assertEquals(ordinal, diagnostic.recordOrdinal)
        assertEquals(fieldRole, diagnostic.fieldRole)
    }

    private fun outputStrings(result: AlipayBatchResult): List<String> =
        result.rows.flatMap { row ->
            when (row) {
                is AlipayRowResult.Accepted ->
                    listOf(
                        row.facts.amountMinor.toString(),
                        row.facts.currencyCode,
                        row.facts.currencyPrecision.toString(),
                        row.facts.occurredAt,
                        row.facts.directionToken,
                        row.facts.statusToken ?: "",
                    )
                is AlipayRowResult.Rejected -> emptyList()
            } +
                row.diagnostics.flatMap {
                    listOf(it.code, it.severity, it.scope, it.inputRef, it.recordOrdinal?.toString() ?: "", it.fieldRole ?: "")
                }
        }

    // ---- T-01..T-16: per-record parsing (P-01..P-16, fixtures A-01..A-16) ----

    @Test // T-01 / P-01
    fun a01OnlinePaymentExpenseMatchesFrozenFacts() {
        val result = AlipayCsvParser.parse(inputRef, csvBytes(batchARows()))
        assertEquals(AlipayBatchOutcome.PARTIAL, result.outcome)

        val a01 = accepted(result.rows, 0)
        assertEquals(ImportRecordKind.ORDINARY_FLOW_SOURCE, a01.recordKind)
        assertEquals(a01Facts, a01.facts)
        assertEquals(ImportCompleteness.VALID_COMPLETE, a01.completeness)
        assertEquals(emptyList(), a01.diagnostics)
    }

    @Test // T-02 / P-02
    fun a02ScanPaymentExpenseMatchesFrozenFacts() {
        val result = AlipayCsvParser.parse(inputRef, csvBytes(batchARows()))

        val a02 = accepted(result.rows, 1)
        assertEquals(a02Facts, a02.facts)
        assertEquals(ImportCompleteness.VALID_COMPLETE, a02.completeness)
        assertEquals(emptyList(), a02.diagnostics)
    }

    @Test // T-03 / P-03
    fun a03IncomeWithEmptyMerchantOrderNumberIsLegal() {
        val result = AlipayCsvParser.parse(inputRef, csvBytes(batchARows()))

        // Single-tab row shape (merchant order number empty) is a legal frozen variant.
        val a03 = accepted(result.rows, 2)
        assertEquals(a03Facts, a03.facts)
        assertEquals(ImportCompleteness.VALID_COMPLETE, a03.completeness)
        assertEquals(emptyList(), a03.diagnostics)
    }

    @Test // T-04 / P-04
    fun a04NeutralDirectionStaysRawAndValidIncomplete() {
        val result = AlipayCsvParser.parse(inputRef, csvBytes(batchARows()))

        val a04 = accepted(result.rows, 3)
        assertEquals(
            ImportSourceFacts(4560, "CNY", 2, "2026-08-09T21:15:30+08:00", "不计收支", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1),
            a04.facts,
        )
        assertEquals(ImportCompleteness.VALID_INCOMPLETE, a04.completeness)
        assertEquals(1, a04.diagnostics.size)
        assertDiagnostic(a04.diagnostics[0], "REQUIRED_FACT_UNRESOLVED", "incomplete", "field", 3, "direction")
    }

    @Test // T-05 / P-05
    fun a05ClosedStatusStaysRawAndValidIncomplete() {
        val result = AlipayCsvParser.parse(inputRef, csvBytes(batchARows()))

        val a05 = accepted(result.rows, 4)
        assertEquals(
            ImportSourceFacts(2000, "CNY", 2, "2026-08-10T09:30:00+08:00", "out", "交易关闭", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1),
            a05.facts,
        )
        assertEquals(ImportCompleteness.VALID_INCOMPLETE, a05.completeness)
        assertEquals(1, a05.diagnostics.size)
        assertDiagnostic(a05.diagnostics[0], "REQUIRED_FACT_UNRESOLVED", "incomplete", "field", 4, "status")
    }

    @Test // T-06 / P-06
    fun a06ZeroAmountIsValidComplete() {
        val result = AlipayCsvParser.parse(inputRef, csvBytes(batchARows()))

        val a06 = accepted(result.rows, 5)
        assertEquals(
            ImportSourceFacts(0, "CNY", 2, "2026-08-10T08:00:20+08:00", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1),
            a06.facts,
        )
        assertEquals(ImportCompleteness.VALID_COMPLETE, a06.completeness)
        assertEquals(emptyList(), a06.diagnostics)
    }

    @Test // T-07 / P-07
    fun a07AccountDepositWithdrawalRejectedAsUnsupportedTxType() {
        val result = AlipayCsvParser.parse(inputRef, csvBytes(batchARows()))

        val a07 = rejected(result.rows, 6)
        assertDiagnostic(a07.diagnostics.single(), "SPINE_ALIPAY_UNSUPPORTED_TX_TYPE", "unsupported", "record", 6)
    }

    @Test // T-08 / P-08
    fun a08TransferRedPacketRejectedAsUnsupportedTxType() {
        val result = AlipayCsvParser.parse(inputRef, csvBytes(batchARows()))

        val a08 = rejected(result.rows, 7)
        assertDiagnostic(a08.diagnostics.single(), "SPINE_ALIPAY_UNSUPPORTED_TX_TYPE", "unsupported", "record", 7)
    }

    @Test // T-09 / P-09
    fun a09RefundStatusRejectedByJudgmentOrderOne() {
        val result = AlipayCsvParser.parse(inputRef, csvBytes(batchARows()))

        // Status contains the refund marker; judgment order 1 wins over type routing.
        val a09 = rejected(result.rows, 8)
        assertDiagnostic(a09.diagnostics.single(), "SPINE_ALIPAY_REFUND_UNSUPPORTED", "unsupported", "record", 8)
    }

    // row is no longer a typed rejection; it routes to the credit repayment source)
    @Test // T-10 / P-10 (P4-06 registered amendment, D-107 section 1: the 信用借还×不计收支×还款
    fun a10CreditRepaymentRowRoutesToCreditRepaymentSource() {
        val result = AlipayCsvParser.parse(inputRef, csvBytes(batchARows()))

        val a10 = accepted(result.rows, 9)
        assertEquals(ImportRecordKind.CREDIT_REPAYMENT_SOURCE, a10.recordKind)
        assertEquals(
            ImportSourceFacts(50000, "CNY", 2, "2026-08-11T12:00:00+08:00", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1),
            a10.facts,
        )
        assertEquals(ImportCompleteness.VALID_COMPLETE, a10.completeness)
        assertEquals(emptyList(), a10.diagnostics)
        assertEquals(ImportPaymentProfile(ImportPaymentVariant.CREDIT_REPAYMENT, null, null), a10.paymentProfile)
    }

    @Test // T-11 / P-11
    fun a11ProxyPaymentRejectedAsUnsupportedTxType() {
        val result = AlipayCsvParser.parse(inputRef, csvBytes(batchARows()))

        val a11 = rejected(result.rows, 10)
        assertDiagnostic(a11.diagnostics.single(), "SPINE_ALIPAY_UNSUPPORTED_TX_TYPE", "unsupported", "record", 10)
    }

    @Test // T-12 / P-12
    fun a12UnknownCategoryTokenRejectedAsUnknownToken() {
        val result = AlipayCsvParser.parse(inputRef, csvBytes(batchARows()))

        val a12 = rejected(result.rows, 11)
        assertDiagnostic(a12.diagnostics.single(), "SPINE_ALIPAY_UNKNOWN_TOKEN", "unsupported", "record", 11)
    }

    @Test // T-13 / P-13
    fun a13NonNumericAmountRejectedAsFieldAmountInvalid() {
        val result = AlipayCsvParser.parse(inputRef, csvBytes(batchARows()))

        val a13 = rejected(result.rows, 12)
        assertDiagnostic(a13.diagnostics.single(), "FIELD_AMOUNT_INVALID", "record_error", "field", 12, "amount")
    }

    @Test // T-14 / P-14
    fun a14BadTimeShapeRejectedAsFieldTimeInvalid() {
        val result = AlipayCsvParser.parse(inputRef, csvBytes(batchARows()))

        val a14 = rejected(result.rows, 13)
        assertDiagnostic(a14.diagnostics.single(), "FIELD_TIME_INVALID", "record_error", "field", 13, "occurred_at")
    }

    @Test // T-15 / P-15
    fun a15NegativeAmountRejectedAsFieldAmountInvalid() {
        val result = AlipayCsvParser.parse(inputRef, csvBytes(batchARows()))

        val a15 = rejected(result.rows, 14)
        assertDiagnostic(a15.diagnostics.single(), "FIELD_AMOUNT_INVALID", "record_error", "field", 14, "amount")
    }

    @Test // T-16 / P-16
    fun a16OneDecimalAmountRejectedAsFieldAmountInvalid() {
        val result = AlipayCsvParser.parse(inputRef, csvBytes(batchARows()))

        val a16 = rejected(result.rows, 15)
        assertDiagnostic(a16.diagnostics.single(), "FIELD_AMOUNT_INVALID", "record_error", "field", 15, "amount")
    }

    // ---- T-17..T-23: batch-level parsing (P-17..P-23) ----

    @Test // T-17 / P-17 (P4-06 amendment: A-10 accepted, so 7 records / 11 diagnostics)
    fun wholeBatchIsPartialWithSevenRecordsAndElevenDiagnostics() {
        val result = AlipayCsvParser.parse(inputRef, csvBytes(batchARows()))
        assertEquals(AlipayBatchOutcome.PARTIAL, result.outcome)
        assertNull(result.diagnostic)

        assertEquals(16, result.rows.size)
        val acceptedRows = result.rows.filterIsInstance<AlipayRowResult.Accepted>()
        assertEquals(7, acceptedRows.size)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 9), acceptedRows.map { it.recordOrdinal })
        assertEquals(5, acceptedRows.count { it.completeness == ImportCompleteness.VALID_COMPLETE })
        assertEquals(2, acceptedRows.count { it.completeness == ImportCompleteness.VALID_INCOMPLETE })
        assertEquals(9, result.rows.count { it is AlipayRowResult.Rejected })
        result.rows.filterIsInstance<AlipayRowResult.Rejected>().forEach {
            assertEquals(1, it.diagnostics.size, "rejected row carries exactly one diagnostic")
        }

        val byCode =
            result.rows
                .flatMap { it.diagnostics }
                .groupingBy { it.code }
                .eachCount()
        assertEquals(
            mapOf(
                "REQUIRED_FACT_UNRESOLVED" to 2,
                "SPINE_ALIPAY_UNSUPPORTED_TX_TYPE" to 3,
                "SPINE_ALIPAY_REFUND_UNSUPPORTED" to 1,
                "SPINE_ALIPAY_UNKNOWN_TOKEN" to 1,
                "FIELD_AMOUNT_INVALID" to 3,
                "FIELD_TIME_INVALID" to 1,
            ),
            byCode,
        )

        // Frozen 11-entry diagnostic multiset (message is never compared, D-097:1459).
        val multiset =
            result.rows
                .flatMap { row -> row.diagnostics.map { Triple(it.code, it.recordOrdinal, it.fieldRole) } }
                .sortedWith(compareBy({ it.second ?: -1 }, { it.first }))
        assertEquals(
            listOf(
                Triple("REQUIRED_FACT_UNRESOLVED", 3, "direction"),
                Triple("REQUIRED_FACT_UNRESOLVED", 4, "status"),
                Triple("SPINE_ALIPAY_UNSUPPORTED_TX_TYPE", 6, null),
                Triple("SPINE_ALIPAY_UNSUPPORTED_TX_TYPE", 7, null),
                Triple("SPINE_ALIPAY_REFUND_UNSUPPORTED", 8, null),
                Triple("SPINE_ALIPAY_UNSUPPORTED_TX_TYPE", 10, null),
                Triple("SPINE_ALIPAY_UNKNOWN_TOKEN", 11, null),
                Triple("FIELD_AMOUNT_INVALID", 12, "amount"),
                Triple("FIELD_TIME_INVALID", 13, "occurred_at"),
                Triple("FIELD_AMOUNT_INVALID", 14, "amount"),
                Triple("FIELD_AMOUNT_INVALID", 15, "amount"),
            ),
            multiset,
        )
    }

    @Test // T-18 / P-18 / M-01
    fun metadataAreaIsSkippedWithoutAnyPiiLeak() {
        val result = AlipayCsvParser.parse(inputRef, csvBytes(batchARows()))
        val outputs = outputStrings(result)
        val metadataTokens = (0..22).flatMap { listOf("SYN-META-PII-EXPORT-$it", "SYN-META-PII-NICK-$it") }
        metadataTokens.forEach { secret ->
            assertTrue(outputs.none { it == secret || it.contains(secret) }, "metadata value leaked: $secret")
        }
    }

    @Test // T-19 / P-19
    fun headerMismatchAndTruncationRejectBatchWithStructureMismatch() {
        // b1: missing column (eleven tokens + trailing comma = 12 fields).
        val missing = csvBytes(listOf(a01Row()), header = headerLine(AlipaySourceTokens.HEADER_TOKENS.dropLast(1)))
        val missingResult = AlipayCsvParser.parse("batch-p405-b1", missing)
        assertEquals(AlipayBatchOutcome.REJECTED, missingResult.outcome)
        assertEquals(0, missingResult.rows.size)
        assertDiagnostic(
            assertIs(missingResult.diagnostic),
            "STRUCTURE_MISMATCH",
            "fatal",
            "structure",
            expectedInputRef = "batch-p405-b1",
        )

        // b2: extra column (thirteen tokens + trailing comma = 14 fields).
        val extra = csvBytes(listOf(a01Row()), header = headerLine(AlipaySourceTokens.HEADER_TOKENS + "多余"))
        val extraResult = AlipayCsvParser.parse("batch-p405-b2", extra)
        assertEquals(AlipayBatchOutcome.REJECTED, extraResult.outcome)
        assertEquals(0, extraResult.rows.size)
        assertDiagnostic(
            assertIs(extraResult.diagnostic),
            "STRUCTURE_MISMATCH",
            "fatal",
            "structure",
            expectedInputRef = "batch-p405-b2",
        )

        // b3: misplaced columns (direction and amount headers swapped).
        val swappedTokens =
            AlipaySourceTokens.HEADER_TOKENS.mapIndexed { index, token ->
                when (index) {
                    5 -> AlipaySourceTokens.HEADER_TOKENS[6]
                    6 -> AlipaySourceTokens.HEADER_TOKENS[5]
                    else -> token
                }
            }
        val swapped = csvBytes(listOf(a01Row()), header = headerLine(swappedTokens))
        val swappedResult = AlipayCsvParser.parse("batch-p405-b3", swapped)
        assertEquals(AlipayBatchOutcome.REJECTED, swappedResult.outcome)
        assertEquals(0, swappedResult.rows.size)
        assertDiagnostic(
            assertIs(swappedResult.diagnostic),
            "STRUCTURE_MISMATCH",
            "fatal",
            "structure",
            expectedInputRef = "batch-p405-b3",
        )

        // b4: token off by one character (对方账号 -> 对方帐号).
        val offByOneTokens =
            AlipaySourceTokens.HEADER_TOKENS.mapIndexed { index, token ->
                if (index == 3) "对方帐号" else token
            }
        val offByOne = csvBytes(listOf(a01Row()), header = headerLine(offByOneTokens))
        val offByOneResult = AlipayCsvParser.parse("batch-p405-b4", offByOne)
        assertEquals(AlipayBatchOutcome.REJECTED, offByOneResult.outcome)
        assertEquals(0, offByOneResult.rows.size)
        assertDiagnostic(
            assertIs(offByOneResult.diagnostic),
            "STRUCTURE_MISMATCH",
            "fatal",
            "structure",
            expectedInputRef = "batch-p405-b4",
        )

        // g1: truncated ten-line file never reaches the frozen header position.
        val truncated =
            csvText(batchARows())
                .split("\n")
                .take(10)
                .joinToString("\n")
                .toByteArray(gb18030)
        val truncatedResult = AlipayCsvParser.parse("batch-p405-g1", truncated)
        assertEquals(AlipayBatchOutcome.REJECTED, truncatedResult.outcome)
        assertEquals(0, truncatedResult.rows.size)
        assertDiagnostic(
            assertIs(truncatedResult.diagnostic),
            "STRUCTURE_MISMATCH",
            "fatal",
            "structure",
            expectedInputRef = "batch-p405-g1",
        )
    }

    @Test // T-20 / P-20
    fun encodingVariantsMatchAndContainerGuardsMapToFrozenCodes() {
        // batch-p405-c: UTF-8 variant batch, single row with the A-01 facts.
        val utf8 = csvBytes(listOf(a01Row()), charset = Charsets.UTF_8)
        val utf8Result = AlipayCsvParser.parse("batch-p405-c", utf8)
        assertEquals(AlipayBatchOutcome.COMPLETE, utf8Result.outcome)
        val utf8Row = accepted(utf8Result.rows, 0)
        assertEquals(a01Facts, utf8Row.facts)
        assertEquals(ImportCompleteness.VALID_COMPLETE, utf8Row.completeness)
        assertEquals(emptyList(), utf8Row.diagnostics)

        // GB18030 primary path decodes the same facts value-for-value (decode order
        // strict UTF-8 first, GB18030 fallback is deterministic).
        val gb = csvBytes(listOf(a01Row()), charset = gb18030)
        val gbResult = AlipayCsvParser.parse("batch-p405-a", gb)
        assertEquals(AlipayBatchOutcome.COMPLETE, gbResult.outcome)
        val gbRow = accepted(gbResult.rows, 0)
        assertEquals(utf8Row.facts, gbRow.facts)
        assertEquals(utf8Row.completeness, gbRow.completeness)

        // batch-p405-d: empty input is a decode failure.
        val emptyResult = AlipayCsvParser.parse("batch-p405-d", ByteArray(0))
        assertEquals(AlipayBatchOutcome.REJECTED, emptyResult.outcome)
        assertEquals(0, emptyResult.rows.size)
        assertDiagnostic(
            assertIs(emptyResult.diagnostic),
            "INPUT_DECODE_FAILED",
            "fatal",
            "input",
            expectedInputRef = "batch-p405-d",
        )

        // batch-p405-h: PK zip container is not plain-text CSV.
        val zipResult = AlipayCsvParser.parse("batch-p405-h", minimalZipBytes())
        assertEquals(AlipayBatchOutcome.REJECTED, zipResult.outcome)
        assertEquals(0, zipResult.rows.size)
        assertDiagnostic(
            assertIs(zipResult.diagnostic),
            "INPUT_UNSUPPORTED",
            "fatal",
            "input",
            expectedInputRef = "batch-p405-h",
        )

        // OLE2 magic is likewise an unsupported container.
        val ole2 =
            byteArrayOf(
                0xD0.toByte(),
                0xCF.toByte(),
                0x11.toByte(),
                0xE0.toByte(),
                0xA1.toByte(),
                0xB1.toByte(),
                0x1A.toByte(),
                0xE1.toByte(),
            ) + ByteArray(64)
        val ole2Result = AlipayCsvParser.parse("batch-p405-h", ole2)
        assertEquals(AlipayBatchOutcome.REJECTED, ole2Result.outcome)
        assertEquals(0, ole2Result.rows.size)
        assertDiagnostic(
            assertIs(ole2Result.diagnostic),
            "INPUT_UNSUPPORTED",
            "fatal",
            "input",
            expectedInputRef = "batch-p405-h",
        )

        // batch-p405-i: over the frozen byte bound.
        val oversized = ByteArray(AlipaySourceTokens.MAX_INPUT_BYTES + 1)
        val oversizedResult = AlipayCsvParser.parse("batch-p405-i", oversized)
        assertEquals(AlipayBatchOutcome.REJECTED, oversizedResult.outcome)
        assertEquals(0, oversizedResult.rows.size)
        assertDiagnostic(
            assertIs(oversizedResult.diagnostic),
            "INPUT_UNSAFE_OR_OVER_LIMIT",
            "fatal",
            "input",
            expectedInputRef = "batch-p405-i",
        )
    }

    @Test // T-21 / P-21
    fun rowStructureVariantsRejectAtRecordLevelWhileValidRowSurvives() {
        val variants =
            listOf(
                // e1: tab count 0 (trailing tabs stripped from both order columns).
                "batch-p405-e1" to a01Row(txOrderField = "SYN-SECRET-TXNO", merchOrderField = "SYN-SECRET-MERCHNO"),
                // e2: tab count 3 (merchant order number carries two trailing tabs).
                "batch-p405-e2" to a01Row(merchOrderField = "SYN-SECRET-MERCHNO\t\t"),
                // e3: field count 12 (trailing comma missing).
                "batch-p405-e3" to rawRow(a01Fields(), trailingComma = false),
                // e4: field count 14 (extra trailing separator).
                "batch-p405-e4" to (a01Row() + ","),
                // e5: thirteenth field non-empty.
                "batch-p405-e5" to (a01Row() + "SYN-EXTRA-FIELD"),
                // e6: residual CR in a data row.
                "batch-p405-e6" to (a01Row() + "\r"),
                // e7: tab outside the two order-number columns (note column).
                "batch-p405-e7" to a01Row(note = "SYN-SECRET-NOTE\t"),
            )
        for ((ref, variantRow) in variants) {
            val result = AlipayCsvParser.parse(ref, csvBytes(listOf(a01Row(), variantRow)))
            assertEquals(AlipayBatchOutcome.PARTIAL, result.outcome, ref)
            assertEquals(2, result.rows.size, ref)
            assertEquals(a01Facts, accepted(result.rows, 0).facts, ref)
            val bad = rejected(result.rows, 1)
            assertDiagnostic(bad.diagnostics.single(), "STRUCTURE_MISMATCH", "fatal", "record", 1, expectedInputRef = ref)
        }
    }

    @Test // T-22 / P-22
    fun emptyLinesSkippedWithoutOrdinalRenumberingAndHeaderOnlyIsComplete() {
        // batch-p405-f: A-01, two blank lines, A-02, then a trailing EOF blank line.
        val bytes = csvBytes(listOf(a01Row(), "", "", recordRow("扫码支付", "支出", "12.50", "交易成功", "2026-08-05 09:00:00"), ""))
        val result = AlipayCsvParser.parse("batch-p405-f", bytes)
        assertEquals(AlipayBatchOutcome.COMPLETE, result.outcome)
        assertNull(result.diagnostic)
        assertEquals(2, result.rows.size)
        assertEquals(0, result.rows[0].recordOrdinal)
        assertEquals(3, result.rows[1].recordOrdinal)
        assertEquals(12850, accepted(result.rows, 0).facts.amountMinor)
        assertEquals(1250, accepted(result.rows, 3).facts.amountMinor)

        // batch-p405-g2: header only, no data rows.
        val headerOnly = AlipayCsvParser.parse("batch-p405-g2", csvBytes(emptyList()))
        assertEquals(AlipayBatchOutcome.COMPLETE, headerOnly.outcome)
        assertNull(headerOnly.diagnostic)
        assertEquals(0, headerOnly.rows.size)
    }

    @Test // T-23 / P-23
    fun timeVectorsConvertToFrozenOffsetIsoTextDeterministically() {
        val vectors =
            listOf(
                "2026-08-01 12:30:45" to "2026-08-01T12:30:45+08:00",
                "2026-08-05 09:00:00" to "2026-08-05T09:00:00+08:00",
                "2026-08-06 18:45:15" to "2026-08-06T18:45:15+08:00",
                "2026-08-09 21:15:30" to "2026-08-09T21:15:30+08:00",
                "2026-08-10 09:30:00" to "2026-08-10T09:30:00+08:00",
                "2026-08-10 08:00:20" to "2026-08-10T08:00:20+08:00",
            )
        val rows =
            vectors.map { (time, _) -> recordRow("网上支付", "支出", "10.00", "交易成功", time) } +
                listOf("2026-08-01 12:30", "2026-13-01 00:00:00", "", "不是时间").map {
                    recordRow("网上支付", "支出", "10.00", "交易成功", it)
                }
        val bytes = csvBytes(rows)
        val result = AlipayCsvParser.parse(inputRef, bytes)

        vectors.forEachIndexed { index, (_, iso) ->
            assertEquals(iso, accepted(result.rows, index).facts.occurredAt)
        }
        assertDiagnostic(
            rejected(result.rows, 6).diagnostics.single(),
            "FIELD_TIME_INVALID",
            "record_error",
            "field",
            6,
            "occurred_at",
        )
        assertDiagnostic(
            rejected(result.rows, 7).diagnostics.single(),
            "FIELD_TIME_INVALID",
            "record_error",
            "field",
            7,
            "occurred_at",
        )
        assertDiagnostic(
            rejected(result.rows, 8).diagnostics.single(),
            "FIELD_TIME_INVALID",
            "record_error",
            "field",
            8,
            "occurred_at",
        )
        assertDiagnostic(
            rejected(result.rows, 9).diagnostics.single(),
            "FIELD_TIME_INVALID",
            "record_error",
            "field",
            9,
            "occurred_at",
        )

        // Determinism: identical bytes always produce the identical result.
        assertEquals(result, AlipayCsvParser.parse(inputRef, bytes))
    }

    // ---- T-24..T-26: amount shape, token policy, provider-DTO-zero vectors ----

    @Test // T-24
    fun amountShapeVectorsDeriveExactMinorUnitsAtFrozenPrecisionTwo() {
        val valid =
            listOf(
                "128.50" to 12850L,
                "12.50" to 1250L,
                "88.00" to 8800L,
                "0.00" to 0L,
            )
        val validBytes = csvBytes(valid.map { (amount, _) -> recordRow("网上支付", "支出", amount, "交易成功", "2026-08-01 08:00:00") })
        val validResult = AlipayCsvParser.parse(inputRef, validBytes)
        assertEquals(AlipayBatchOutcome.COMPLETE, validResult.outcome)
        valid.forEachIndexed { index, (_, minor) ->
            val row = accepted(validResult.rows, index)
            assertEquals(minor, row.facts.amountMinor)
            assertEquals(2, row.facts.currencyPrecision, "currency_precision is the frozen constant 2")
            assertEquals(ImportCompleteness.VALID_COMPLETE, row.completeness)
        }

        val invalid = listOf("12.5", "88", "12.500", "-10.00", "abc", "", " 10.00")
        val invalidBytes = csvBytes(invalid.map { recordRow("网上支付", "支出", it, "交易成功", "2026-08-01 08:00:00") })
        val invalidResult = AlipayCsvParser.parse(inputRef, invalidBytes)
        assertEquals(AlipayBatchOutcome.PARTIAL, invalidResult.outcome)
        assertEquals(0, invalidResult.rows.count { it is AlipayRowResult.Accepted })
        invalid.indices.forEach { ordinal ->
            val row = rejected(invalidResult.rows, ordinal)
            assertDiagnostic(row.diagnostics.single(), "FIELD_AMOUNT_INVALID", "record_error", "field", ordinal, "amount")
        }
    }

    @Test // T-25
    fun directionAndStatusTokenPolicyMatchesFrozenSets() {
        val rows =
            listOf(
                recordRow("其他", "收入", "10.00", "交易成功", "2026-08-01 10:00:00"),
                recordRow("其他", "支出", "10.00", "交易成功", "2026-08-01 10:01:00"),
                recordRow("其他", "不计收支", "10.00", "交易成功", "2026-08-01 10:02:00"),
                recordRow("其他", "支出", "10.00", "交易关闭", "2026-08-01 10:03:00"),
                recordRow("其他", "支出", "10.00", "等待确认收货", "2026-08-01 10:04:00"),
                recordRow("其他", "不计收支", "10.00", "退款成功", "2026-08-01 10:05:00"),
                recordRow("账户存取", "不计收支", "10.00", "交易成功", "2026-08-01 10:06:00"),
                recordRow("转账红包", "收入", "10.00", "交易成功", "2026-08-01 10:07:00"),
                recordRow("信用借还", "不计收支", "10.00", "还款", "2026-08-01 10:08:00"),
                recordRow("亲友代付", "支出", "10.00", "代付成功", "2026-08-01 10:09:00"),
                recordRow("神秘交易分类", "支出", "10.00", "交易成功", "2026-08-01 10:10:00"),
            )
        val result = AlipayCsvParser.parse(inputRef, csvBytes(rows))
        assertEquals(AlipayBatchOutcome.PARTIAL, result.outcome)

        // Direction mapping: 收入 -> in, 支出 -> out; anything else stays raw + unresolved.
        assertEquals("in", accepted(result.rows, 0).facts.directionToken)
        assertEquals(ImportCompleteness.VALID_COMPLETE, accepted(result.rows, 0).completeness)
        assertEquals("out", accepted(result.rows, 1).facts.directionToken)
        assertEquals(ImportCompleteness.VALID_COMPLETE, accepted(result.rows, 1).completeness)
        val neutral = accepted(result.rows, 2)
        assertEquals("不计收支", neutral.facts.directionToken)
        assertEquals(ImportCompleteness.VALID_INCOMPLETE, neutral.completeness)
        assertDiagnostic(neutral.diagnostics.single(), "REQUIRED_FACT_UNRESOLVED", "incomplete", "field", 2, "direction")

        // Status accepted subset {交易成功}; unmapped status tokens stay raw + unresolved,
        // never rejected.
        assertEquals("settled", accepted(result.rows, 0).facts.statusToken)
        val closed = accepted(result.rows, 3)
        assertEquals("交易关闭", closed.facts.statusToken)
        assertEquals(ImportCompleteness.VALID_INCOMPLETE, closed.completeness)
        assertDiagnostic(closed.diagnostics.single(), "REQUIRED_FACT_UNRESOLVED", "incomplete", "field", 3, "status")
        val awaiting = accepted(result.rows, 4)
        assertEquals("等待确认收货", awaiting.facts.statusToken)
        assertEquals(ImportCompleteness.VALID_INCOMPLETE, awaiting.completeness)
        assertDiagnostic(awaiting.diagnostics.single(), "REQUIRED_FACT_UNRESOLVED", "incomplete", "field", 4, "status")

        // Refund marker wins judgment order 1 even on an accepted type with an
        // unresolved direction.
        assertDiagnostic(
            rejected(result.rows, 5).diagnostics.single(),
            "SPINE_ALIPAY_REFUND_UNSUPPORTED",
            "unsupported",
            "record",
            5,
        )

        // Rejected family set: 账户存取 / 转账红包 / 亲友代付; the 信用借还 不计收支+还款 row
        // now routes to the credit repayment source (P4-06 registered amendment).
        listOf(6, 7, 9).forEach { ordinal ->
            assertDiagnostic(
                rejected(result.rows, ordinal).diagnostics.single(),
                "SPINE_ALIPAY_UNSUPPORTED_TX_TYPE",
                "unsupported",
                "record",
                ordinal,
            )
        }
        val repayment = accepted(result.rows, 8)
        assertEquals(ImportRecordKind.CREDIT_REPAYMENT_SOURCE, repayment.recordKind)
        assertEquals("settled", repayment.facts.statusToken)
        assertEquals(ImportCompleteness.VALID_COMPLETE, repayment.completeness)

        // Unknown category token cannot be routed.
        assertDiagnostic(
            rejected(result.rows, 10).diagnostics.single(),
            "SPINE_ALIPAY_UNKNOWN_TOKEN",
            "unsupported",
            "record",
            10,
        )
    }

    @Test // T-26
    fun orderIdTabInvariantsHoldAndNonPersistedColumnsNeverLeak() {
        // Tab invariant, both directions: two tabs (both order columns present) and one
        // tab (merchant order number empty) are both legal shapes.
        val bothTabs =
            csvBytes(
                listOf(
                    a01Row(),
                    recordRow("其他", "收入", "88.00", "交易成功", "2026-08-06 18:45:15", merchOrderNo = null),
                ),
            )
        val shapeResult = AlipayCsvParser.parse(inputRef, bothTabs)
        assertEquals(AlipayBatchOutcome.COMPLETE, shapeResult.outcome)
        assertEquals(a01Facts, accepted(shapeResult.rows, 0).facts)
        assertEquals(a03Facts, accepted(shapeResult.rows, 1).facts)

        // Provider DTO zero introduction: values of the non-persisted columns
        // (交易对方/对方账号/商品说明/收/付款方式/交易订单号/商家订单号/备注 — the real layout has no
        // 交易号 column, spec §9.2) and the metadata area never intersect the parse output
        // or diagnostics.
        val result = AlipayCsvParser.parse(inputRef, csvBytes(batchARows()))
        val outputs = outputStrings(result)
        val forbidden =
            (0..22).flatMap { listOf("SYN-META-PII-EXPORT-$it", "SYN-META-PII-NICK-$it") } +
                listOf(
                    "SYN-SECRET-COUNTERPARTY",
                    "SYN-SECRET-ACCOUNT",
                    "SYN-SECRET-PRODUCT",
                    "SYN-SECRET-METHOD",
                    "SYN-SECRET-TXNO",
                    "SYN-SECRET-MERCHNO",
                    "SYN-SECRET-NOTE",
                )
        forbidden.forEach { secret ->
            assertTrue(outputs.none { it == secret || it.contains(secret) }, "forbidden value leaked: $secret")
        }
    }

    // ---- §9.5 corrective-amendment consistency tests (real-header / real-layout) ----

    @Test // §9.5(a) / canonical real-header byte acceptance (defect-catcher)
    fun canonicalRealHeaderGbkEncodedIsAcceptedByHeaderMatch() {
        // The 12 canonical real tokens (spec §9.2, byte-verified across 9 real exports on
        // 2026-08-18) are hardcoded as a literal — NOT derived from HEADER_TOKENS — then
        // GBK-encoded and fed to the parser. The byte-exact header match must ACCEPT it
        // (no STRUCTURE_MISMATCH, batch not REJECTED on header). This is the test that
        // would have caught the frozen-header defect: if HEADER_TOKENS held the wrong
        // tokens, this literal header would fail the match and the batch would be REJECTED.
        // Column-name tokens only, no personal data.
        val canonicalHeader =
            "交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注,"
        val bytes = csvBytes(emptyList(), header = canonicalHeader, charset = gb18030)
        val result = AlipayCsvParser.parse("batch-p405-canonical-header", bytes)
        assertEquals(AlipayBatchOutcome.COMPLETE, result.outcome)
        assertNull(result.diagnostic)
        assertEquals(0, result.rows.size)
    }

    @Test // §9.5(b) / real-layout data-row fact extraction
    fun realLayoutDataRowExtractsFactsFromCorrectColumnIndices() {
        // A synthetic data row in the REAL column layout (spec §9.2): time at index 0,
        // product 商品说明 at index 4, amount at index 6, category[1]/direction[5]/
        // status[8]/order[9] (trailing tab)/merchant-order[10] (trailing tab). Asserts
        // facts come from the real indices and the 商品说明 value (fields[4]) never appears
        // in the extracted facts or diagnostics. Synthetic values only.
        val productDescription = "SYN-PRODUCT-DESCRIPTION-VALUE"
        val dataRow =
            rawRow(
                listOf(
                    "2026-08-01 12:30:45",
                    "网上支付",
                    "SYN-SECRET-COUNTERPARTY",
                    "SYN-SECRET-ACCOUNT",
                    productDescription,
                    "支出",
                    "128.50",
                    "",
                    "交易成功",
                    "SYN-SECRET-TXNO\t",
                    "SYN-SECRET-MERCHNO\t",
                    "SYN-SECRET-NOTE",
                ),
            )
        val bytes = csvBytes(listOf(dataRow))
        val result = AlipayCsvParser.parse("batch-p405-real-layout", bytes)
        assertEquals(AlipayBatchOutcome.COMPLETE, result.outcome)

        val row = accepted(result.rows, 0)
        // occurred_at comes from fields[0] (real time column); fields[4] holds the product
        // description, which is not a valid time shape, so acceptance proves the read index.
        assertEquals("2026-08-01T12:30:45+08:00", row.facts.occurredAt)
        // amount_minor comes from fields[6] (real amount column); no other field holds "128.50".
        assertEquals(12850L, row.facts.amountMinor)
        assertEquals("CNY", row.facts.currencyCode)
        assertEquals(2, row.facts.currencyPrecision)
        // category[1] routed (网上支付 accepted), direction[5] mapped, status[8] mapped.
        assertEquals("out", row.facts.directionToken)
        assertEquals("settled", row.facts.statusToken)
        assertEquals(ImportCompleteness.VALID_COMPLETE, row.completeness)
        assertEquals(emptyList<AlipayDiagnostic>(), row.diagnostics)

        // 商品说明 (fields[4]) never appears in any extracted fact or diagnostic string.
        val outputs = outputStrings(result)
        assertTrue(
            outputs.none { it == productDescription || it.contains(productDescription) },
            "商品说明 value leaked into facts/diagnostics: $productDescription",
        )
    }
}
