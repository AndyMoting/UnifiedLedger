package com.unifiedledger.application.import.alipay

import com.unifiedledger.application.IMPORT_FUNDING_RULE_LEGACY_SETTLED
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportFundingState
import com.unifiedledger.application.ImportPaymentProfile
import com.unifiedledger.application.ImportPaymentVariant
import com.unifiedledger.application.ImportRecordKind
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P4-06 slice 1 (RL-05 credit) parser oracle (D-107 implementation spec sections 2/6):
 * payment-leg whitelist, frozen bracket stripping, the judgment-order 0-6 vectors,
 * the three new diagnostic codes, family status gates and direction derivation, and
 * the privacy boundary (only normalized whitelist tokens ever leave the parser).
 *
 * All inputs are synthetic and provider-neutral. The frozen file shape is the P4-05
 * shape; column 7 (收/付款方式) is the composite leg token under test. Every rejected
 * row below also proves zero record output (a rejected row produces no record, no
 * candidate and no profile: the D-099 zero-write discipline).
 */
class AlipayCsvParserCreditJvmTest {

    private val inputRef = "batch-p406-a"
    private val gb18030: Charset = Charset.forName("GB18030")

    private fun metadataLines(): List<String> =
        (0..22).map { "SYN-META-PII-EXPORT-$it,SYN-META-PII-NICK-$it" }

    private fun headerLine(): String = AlipaySourceTokens.HEADER_TOKENS.joinToString(",") + ","

    /** Synthetic credit-shape data row; [method] is the raw column-7 composite token. */
    private fun creditRow(
        category: String,
        direction: String,
        amount: String,
        status: String,
        time: String,
        method: String,
        merchOrderNo: String? = "SYN-SECRET-MERCHNO",
    ): String = listOf(
        time, category, "SYN-SECRET-COUNTERPARTY", "SYN-SECRET-ACCOUNT",
        "SYN-SECRET-PRODUCT", direction, amount, method, status,
        "SYN-SECRET-TXNO\t", merchOrderNo?.let { "$it\t" } ?: "", "SYN-SECRET-NOTE",
    ).joinToString(",") + ","

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

    private val directProfile = ImportPaymentProfile(ImportPaymentVariant.CREDIT_EXPENSE_DIRECT, null, "花呗")
    private val refundProfile = ImportPaymentProfile(ImportPaymentVariant.CREDIT_EXPENSE_REFUND, null, "花呗")

    private fun v3Facts(amountMinor: Long, time: String, direction: String, status: String?) =
        com.unifiedledger.application.ImportSourceFacts(
            amountMinor, "CNY", 2, time, direction, status,
            ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1,
        )

    // ---- Matrix 1: credit expense direct variant (judgment order 6e) ----

    @Test
    fun installmentMethodFormRoutesToCreditExpenseDirectVariant() {
        val rows = listOf(
            creditRow("网上支付", "支出", "100.00", "交易成功", "2026-08-01 12:30:45", "花呗分期(3期)"),
            creditRow("扫码支付", "支出", "80.00", "交易成功", "2026-08-01 12:31:45", "花呗"),
            creditRow("网上支付", "支出", "60.00", "交易成功", "2026-08-01 12:32:45", "花呗分期(12期)"),
        )
        val result = AlipayCsvParser.parse(inputRef, csvBytes(rows))
        assertEquals(AlipayBatchOutcome.COMPLETE, result.outcome)

        val a = accepted(result.rows, 0)
        assertEquals(ImportRecordKind.CREDIT_EXPENSE_SOURCE, a.recordKind)
        assertEquals(v3Facts(10000, "2026-08-01T12:30:45+08:00", "out", "settled"), a.facts)
        assertEquals(ImportCompleteness.VALID_COMPLETE, a.completeness)
        assertEquals(emptyList(), a.diagnostics)
        assertEquals(directProfile, a.paymentProfile)

        // 花呗 and 花呗分期(N期) fold onto the same credit-leg token (set semantics).
        assertEquals(directProfile, accepted(result.rows, 1).paymentProfile)
        assertEquals(directProfile, accepted(result.rows, 2).paymentProfile)
        assertEquals("花呗", accepted(result.rows, 1).paymentProfile?.creditLegKindToken)
    }

    @Test
    fun creditExpenseNonSuccessStatusStaysValidIncompleteWithRawStatus() {
        val rows = listOf(
            creditRow("网上支付", "支出", "100.00", "交易关闭", "2026-08-01 12:30:45", "花呗分期(3期)"),
        )
        val result = AlipayCsvParser.parse(inputRef, csvBytes(rows))
        val row = accepted(result.rows, 0)
        assertEquals(ImportRecordKind.CREDIT_EXPENSE_SOURCE, row.recordKind)
        assertEquals(v3Facts(10000, "2026-08-01T12:30:45+08:00", "out", "交易关闭"), row.facts)
        assertEquals(ImportCompleteness.VALID_INCOMPLETE, row.completeness)
        assertEquals(1, row.diagnostics.size)
        assertEquals("REQUIRED_FACT_UNRESOLVED", row.diagnostics[0].code)
        assertEquals("status", row.diagnostics[0].fieldRole)
        assertEquals(directProfile, row.paymentProfile)
    }

    // ---- Matrix 1: credit repayment family (judgment order 3d/3e) ----

    @Test
    fun creditBorrowRepayFamilyRoutesRepaymentWithDerivedDirectionAndAdvisoryAssetLeg() {
        val rows = listOf(
            creditRow("信用借还", "不计收支", "56.20", "还款", "2026-08-11 12:00:00", ""),
            creditRow("信用借还", "不计收支", "500.00", "还款", "2026-08-11 12:05:00", "余额宝"),
            creditRow("信用借还", "不计收支", "500.00", "还款", "2026-08-11 12:06:00", "招商银行储蓄卡(0123)"),
        )
        val result = AlipayCsvParser.parse(inputRef, csvBytes(rows))
        assertEquals(AlipayBatchOutcome.COMPLETE, result.outcome)

        val a = accepted(result.rows, 0)
        assertEquals(ImportRecordKind.CREDIT_REPAYMENT_SOURCE, a.recordKind)
        assertEquals(v3Facts(5620, "2026-08-11T12:00:00+08:00", "out", "settled"), a.facts)
        assertEquals(ImportCompleteness.VALID_COMPLETE, a.completeness)
        assertEquals(emptyList(), a.diagnostics)
        assertEquals(ImportPaymentProfile(ImportPaymentVariant.CREDIT_REPAYMENT, null, null), a.paymentProfile)

        val b = accepted(result.rows, 1)
        assertEquals(ImportPaymentProfile(ImportPaymentVariant.CREDIT_REPAYMENT, "余额宝", null), b.paymentProfile)

        // Frozen 4-digit mask stripping (§2.2): 招商银行储蓄卡(0123) -> 招商银行储蓄卡.
        val c = accepted(result.rows, 2)
        assertEquals(ImportPaymentProfile(ImportPaymentVariant.CREDIT_REPAYMENT, "招商银行储蓄卡", null), c.paymentProfile)
    }

    @Test
    fun creditRepaymentNonSuccessStatusStaysValidIncompleteWithRawStatus() {
        val rows = listOf(
            creditRow("信用借还", "不计收支", "56.20", "交易成功", "2026-08-11 12:00:00", ""),
            creditRow("信用借还", "不计收支", "56.20", "交易关闭", "2026-08-11 12:01:00", ""),
        )
        val result = AlipayCsvParser.parse(inputRef, csvBytes(rows))
        listOf("交易成功", "交易关闭").forEachIndexed { index, raw ->
            val row = accepted(result.rows, index)
            assertEquals(ImportRecordKind.CREDIT_REPAYMENT_SOURCE, row.recordKind)
            assertEquals(v3Facts(5620, "2026-08-11T12:0${index}:00+08:00", "out", raw), row.facts)
            assertEquals(ImportCompleteness.VALID_INCOMPLETE, row.completeness)
            assertEquals("REQUIRED_FACT_UNRESOLVED", row.diagnostics.single().code)
            assertEquals("status", row.diagnostics.single().fieldRole)
        }
    }

    // ---- Matrix 1: credit refund variant (judgment order 1c) ----

    @Test
    fun creditOnlyRefundMarkerRoutesToRefundVariantWithRefundSettledStatus() {
        val rows = listOf(
            creditRow("网上支付", "不计收支", "15.35", "退款成功", "2026-08-12 09:00:00", "花呗分期(3期)"),
            creditRow("网上支付", "不计收支", "15.35", "退款成功", "2026-08-12 09:01:00", "花呗"),
            // Set semantics: 花呗&花呗分期(3期) collapses to one credit leg.
            creditRow("网上支付", "不计收支", "15.35", "退款成功", "2026-08-12 09:02:00", "花呗&花呗分期(3期)"),
        )
        val result = AlipayCsvParser.parse(inputRef, csvBytes(rows))
        assertEquals(AlipayBatchOutcome.COMPLETE, result.outcome)
        rows.indices.forEach { index ->
            val row = accepted(result.rows, index)
            assertEquals(ImportRecordKind.CREDIT_EXPENSE_SOURCE, row.recordKind)
            assertEquals(
                v3Facts(1535, "2026-08-12T09:0${index}:00+08:00", "in", "refund_settled"),
                row.facts,
            )
            assertEquals(ImportCompleteness.VALID_COMPLETE, row.completeness)
            assertEquals(emptyList(), row.diagnostics)
            assertEquals(refundProfile, row.paymentProfile)
        }
    }

    @Test
    fun creditRefundNonSuccessStatusStaysValidIncompleteAndOtherRefundShapesStayRejected() {
        val rows = listOf(
            creditRow("网上支付", "不计收支", "15.35", "退款关闭", "2026-08-12 09:00:00", "花呗"),
            creditRow("网上支付", "支出", "15.35", "退款成功", "2026-08-12 09:01:00", "花呗"),
            creditRow("网上支付", "不计收支", "15.35", "退款成功", "2026-08-12 09:02:00", ""),
            creditRow("网上支付", "不计收支", "15.35", "退款成功", "2026-08-12 09:03:00", "余额宝"),
            creditRow("网上支付", "不计收支", "15.35", "退款成功", "2026-08-12 09:04:00", "余额宝&花呗"),
        )
        val result = AlipayCsvParser.parse(inputRef, csvBytes(rows))

        // 1c negative: raw status preserved, zero rejection upgrade.
        val pending = accepted(result.rows, 0)
        assertEquals(ImportRecordKind.CREDIT_EXPENSE_SOURCE, pending.recordKind)
        assertEquals(v3Facts(1535, "2026-08-12T09:00:00+08:00", "in", "退款关闭"), pending.facts)
        assertEquals(ImportCompleteness.VALID_INCOMPLETE, pending.completeness)
        assertEquals(refundProfile, pending.paymentProfile)

        // 1b: 支出 direction refund keeps the P4-05 rejection.
        assertEquals("SPINE_ALIPAY_REFUND_UNSUPPORTED", rejected(result.rows, 1).diagnostics.single().code)
        // 1d: asset-only / empty / mixed refund shapes keep the P4-05 rejection.
        listOf(2, 3, 4).forEach { ordinal ->
            assertEquals(
                "SPINE_ALIPAY_REFUND_UNSUPPORTED",
                rejected(result.rows, ordinal).diagnostics.single().code,
            )
        }
    }

    // ---- Matrix 3: non-whitelist legs (SPINE_ALIPAY_UNKNOWN_PAYMENT_LEG) ----

    @Test
    fun nonWhitelistLegsRejectTypedWithZeroRecords() {
        val vectors = listOf(
            "红包", "优惠", "花呗立减", "闪购支付红包", "网上消费红包", "支付宝随机立减", "到店支付立减券",
            "他人代付账户", "零钱", "零钱通", "数字人民币钱包", "信用卡", "(0123)", "花呗(abc)",
            "招商银行储蓄卡(123)", "招商银行储蓄卡(01234)", "花呗分期", "账户余额(个人余额x)", "余额宝(1234",
            "花呗&支付宝随机立减", "余额宝&", "SYN-SECRET-METHOD",
        )
        val rows = vectors.mapIndexed { index, method ->
            creditRow("网上支付", "支出", "10.00", "交易成功", "2026-08-13 %02d:00:00".format(index), method)
        }
        val result = AlipayCsvParser.parse(inputRef, csvBytes(rows))
        assertEquals(AlipayBatchOutcome.PARTIAL, result.outcome)
        assertEquals(0, result.rows.count { it is AlipayRowResult.Accepted })
        vectors.indices.forEach { ordinal ->
            val diagnostic = rejected(result.rows, ordinal).diagnostics.single()
            assertEquals("SPINE_ALIPAY_UNKNOWN_PAYMENT_LEG", diagnostic.code)
            assertEquals("unsupported", diagnostic.severity)
            assertEquals("record", diagnostic.scope)
            assertEquals(inputRef, diagnostic.inputRef)
            assertEquals(ordinal, diagnostic.recordOrdinal)
            assertNull(diagnostic.fieldRole)
        }
    }

    // ---- Matrix 4: mixed legs fail closed slice-1; directionality ----

    @Test
    fun mixedLegExpenseFailsClosedAndNeutralAndIncomeFollowFrozenOrderSix() {
        val rows = listOf(
            creditRow("网上支付", "支出", "120.00", "交易成功", "2026-08-14 08:00:00", "余额宝&花呗"),
            creditRow("网上支付", "不计收支", "120.00", "交易成功", "2026-08-14 08:01:00", "余额宝&花呗"),
            creditRow("网上支付", "收入", "120.00", "交易成功", "2026-08-14 08:02:00", "余额宝&花呗"),
            creditRow("网上支付", "收入", "120.00", "交易成功", "2026-08-14 08:03:00", "花呗"),
        )
        val result = AlipayCsvParser.parse(inputRef, csvBytes(rows))

        // 6d: slice-2 fail-closed, no half-confirmed state.
        assertEquals("SPINE_ALIPAY_MIXED_PAYMENT_UNSUPPORTED", rejected(result.rows, 0).diagnostics.single().code)

        // 6g: unmapped direction keeps the A-04 ordinary behavior; legs never persist.
        val neutral = accepted(result.rows, 1)
        assertEquals(ImportRecordKind.ORDINARY_FLOW_SOURCE, neutral.recordKind)
        assertEquals("不计收支", neutral.facts.directionToken)
        assertEquals(ImportCompleteness.VALID_INCOMPLETE, neutral.completeness)
        assertNull(neutral.paymentProfile)
        assertEquals(1, neutral.diagnostics.size)
        assertEquals("direction", neutral.diagnostics.single().fieldRole)

        // 6f: credit leg with income direction is defensively rejected.
        assertEquals("SPINE_ALIPAY_CREDIT_INCOME_UNSUPPORTED", rejected(result.rows, 2).diagnostics.single().code)
        assertEquals("SPINE_ALIPAY_CREDIT_INCOME_UNSUPPORTED", rejected(result.rows, 3).diagnostics.single().code)
    }

    // ---- Matrix 5: family negative shapes and defense ----

    @Test
    fun creditFamilyNegativeShapesKeepTypedRejections() {
        val rows = listOf(
            creditRow("信用借还", "支出", "10.00", "还款", "2026-08-15 08:00:00", ""),
            creditRow("信用借还", "收入", "10.00", "还款", "2026-08-15 08:01:00", ""),
            creditRow("信用借还", "不计收支", "10.00", "还款", "2026-08-15 08:02:00", "花呗"),
            creditRow("信用借还", "不计收支", "10.00", "还款", "2026-08-15 08:03:00", "余额宝&招商银行储蓄卡(0123)"),
            creditRow("信用借还", "不计收支", "10.00", "还款", "2026-08-15 08:04:00", "红包"),
        )
        val result = AlipayCsvParser.parse(inputRef, csvBytes(rows))
        assertEquals(0, result.rows.count { it is AlipayRowResult.Accepted })
        // 3b: family membership gate (direction != 不计收支).
        assertEquals("SPINE_ALIPAY_UNSUPPORTED_TX_TYPE", rejected(result.rows, 0).diagnostics.single().code)
        assertEquals("SPINE_ALIPAY_UNSUPPORTED_TX_TYPE", rejected(result.rows, 1).diagnostics.single().code)
        // 3c: a credit leg inside the repayment family has no anchor.
        assertEquals("SPINE_ALIPAY_UNSUPPORTED_TX_TYPE", rejected(result.rows, 2).diagnostics.single().code)
        // 3d pre-gate: more than one distinct whitelisted asset leg.
        assertEquals("SPINE_ALIPAY_UNKNOWN_PAYMENT_LEG", rejected(result.rows, 3).diagnostics.single().code)
        // 3a: non-whitelist leg.
        assertEquals("SPINE_ALIPAY_UNKNOWN_PAYMENT_LEG", rejected(result.rows, 4).diagnostics.single().code)
    }

    @Test
    fun assetOnlyOrEmptyLegsKeepOrdinaryV1PathWithZeroLegPersistence() {
        val rows = listOf(
            creditRow("网上支付", "支出", "128.50", "交易成功", "2026-08-16 08:00:00", ""),
            creditRow("网上支付", "支出", "128.50", "交易成功", "2026-08-16 08:01:00", "账户余额(个人余额)"),
            creditRow("网上支付", "支出", "128.50", "交易成功", "2026-08-16 08:02:00", "余额"),
            creditRow("网上支付", "支出", "128.50", "交易成功", "2026-08-16 08:03:00", "余额宝(个人余额)"),
            creditRow("网上支付", "不计收支", "10.00", "还款", "2026-08-16 08:04:00", ""),
        )
        val result = AlipayCsvParser.parse(inputRef, csvBytes(rows))
        assertEquals(AlipayBatchOutcome.PARTIAL, result.outcome)
        (0..3).forEach { ordinal ->
            val row = accepted(result.rows, ordinal)
            assertEquals(ImportRecordKind.ORDINARY_FLOW_SOURCE, row.recordKind)
            assertEquals(ImportCompleteness.VALID_COMPLETE, row.completeness)
            assertNull(row.paymentProfile, "ordinary rows never persist a leg profile")
        }
        // Ordinary STATUS_TOKEN_MAP is not extended by the family mappings: a 还款
        // status on an ordinary row stays raw + unresolved (P4-05 A-05 shape).
        val raw = accepted(result.rows, 4)
        assertEquals(ImportRecordKind.ORDINARY_FLOW_SOURCE, raw.recordKind)
        assertEquals("还款", raw.facts.statusToken)
        assertEquals(ImportCompleteness.VALID_INCOMPLETE, raw.completeness)
        assertNull(raw.paymentProfile)
    }

    // ---- Matrix 5/2: judgment order 1a precedes 1b; RL-04 branch never reads column 7 ----

    @Test
    fun refundLegGatePrecedesRefundShapeGateAndInvestmentBranchIgnoresColumnSeven() {
        // The RL-04 row uses the frozen subtype in the product-description column.
        val rows = listOf(
            creditRow("网上支付", "支出", "15.35", "退款成功", "2026-08-17 08:00:00", "红包"),
            listOf(
                "2026-08-17 08:01:00", "投资理财", "SYN-SECRET-COUNTERPARTY", "SYN-SECRET-ACCOUNT",
                "余额宝-自动转入", "不计收支", "10.00", "红包", "交易成功",
                "SYN-SECRET-TXNO\t", "SYN-SECRET-MERCHNO\t", "SYN-SECRET-NOTE",
            ).joinToString(",") + ",",
        )
        val result = AlipayCsvParser.parse(inputRef, csvBytes(rows))
        // 1a leg gate wins over the refund shape gate.
        assertEquals("SPINE_ALIPAY_UNKNOWN_PAYMENT_LEG", rejected(result.rows, 0).diagnostics.single().code)
        // RL-04 frozen behavior: the 投资理财 branch never reads column 7 (D-102).
        val transfer = accepted(result.rows, 1)
        assertEquals(ImportRecordKind.TRANSFER_FLOW_SOURCE, transfer.recordKind)
        assertEquals(ImportCompleteness.VALID_COMPLETE, transfer.completeness)
        assertNull(transfer.paymentProfile)
    }

    // ---- Matrix 2/§2.3 closing rule: v3 branches reuse the frozen amount/time parse ----

    @Test
    fun v3BranchesReuseFrozenAmountAndTimeParsing() {
        val rows = listOf(
            creditRow("网上支付", "支出", "abc", "交易成功", "2026-08-18 08:00:00", "花呗"),
            creditRow("网上支付", "支出", "10.00", "交易成功", "不是时间", "花呗"),
            creditRow("信用借还", "不计收支", "12.5", "还款", "2026-08-18 08:02:00", ""),
            creditRow("网上支付", "不计收支", "abc", "退款成功", "2026-08-18 08:03:00", "花呗"),
        )
        val result = AlipayCsvParser.parse(inputRef, csvBytes(rows))
        assertEquals("FIELD_AMOUNT_INVALID", rejected(result.rows, 0).diagnostics.single().code)
        assertEquals("FIELD_TIME_INVALID", rejected(result.rows, 1).diagnostics.single().code)
        assertEquals("FIELD_AMOUNT_INVALID", rejected(result.rows, 2).diagnostics.single().code)
        assertEquals("FIELD_AMOUNT_INVALID", rejected(result.rows, 3).diagnostics.single().code)
    }

    // ---- Matrix 6: bracket stripping and the privacy boundary ----

    @Test
    fun normalizedTokensPersistButRawAnnotationsAndMasksNeverLeak() {
        val rows = listOf(
            creditRow("网上支付", "支出", "100.00", "交易成功", "2026-08-19 08:00:00", "花呗分期(3期)"),
            creditRow("信用借还", "不计收支", "56.20", "还款", "2026-08-19 08:01:00", "招商银行储蓄卡(4567)"),
            creditRow("网上支付", "不计收支", "15.35", "退款成功", "2026-08-19 08:02:00", "花呗分期(12期)"),
        )
        val result = AlipayCsvParser.parse(inputRef, csvBytes(rows))
        val outputs = mutableListOf<String?>()
        result.rows.forEach { row ->
            if (row is AlipayRowResult.Accepted) {
                outputs += row.facts.amountMinor.toString()
                outputs += row.facts.currencyCode
                outputs += row.facts.currencyPrecision.toString()
                outputs += row.facts.occurredAt
                outputs += row.facts.directionToken
                outputs += row.facts.statusToken
                outputs += row.paymentProfile?.assetLegKindToken
                outputs += row.paymentProfile?.creditLegKindToken
            }
            row.diagnostics.forEach {
                outputs += it.code
                outputs += it.severity
                outputs += it.scope
                outputs += it.inputRef
                outputs += it.recordOrdinal?.toString()
                outputs += it.fieldRole
            }
        }
        // Normalized whitelist tokens are the only column-7 content that ever persists.
        assertTrue(outputs.any { it == "花呗" })
        assertTrue(outputs.any { it == "招商银行储蓄卡" })
        // Raw bracket annotations, mask digits and the installment form never leak.
        listOf("花呗分期", "(3期)", "(12期)", "(4567)", "4567", "(个人余额)", "SYN-SECRET-METHOD").forEach { secret ->
            assertTrue(
                outputs.none { it != null && (it == secret || it.contains(secret)) },
                "forbidden column-7 content leaked: $secret",
            )
        }
    }
}
