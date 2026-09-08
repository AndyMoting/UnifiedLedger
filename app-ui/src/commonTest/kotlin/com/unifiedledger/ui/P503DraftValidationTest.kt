package com.unifiedledger.ui

import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.ParseManualExpenseOccurredAt
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class P503DraftValidationTest {
    // 固定时钟 2026-09-08T12:00:00Z：Asia/Shanghai 本地年为 2026（D-138 规格 §2.1 年补齐）。
    private val clock = LedgerClock { Instant.parse("2026-09-08T12:00:00Z") }
    private val validation = P503DraftValidation(ParseManualExpenseAmount(), ParseManualExpenseOccurredAt(), clock)
    private val occurredAt = Instant.parse("2026-01-15T00:30:00Z")

    @Test
    fun occurredAtTextMustReconcileToTheDraftInstant() {
        // Displayed text matches the draft instant: Continue may proceed.
        assertTrue(validation.occurredAtTextReconciles(occurredAt.toString(), occurredAt))
        // Blank text reconciles to a missing occurred-at (the reducer reports the field error).
        assertTrue(validation.occurredAtTextReconciles("", null))
        // Garbage text with a stale valid draft must NOT reconcile (finding P503IMPL-Q-001).
        assertFalse(validation.occurredAtTextReconciles("not-a-time", occurredAt))
        // A different valid instant must NOT reconcile.
        assertFalse(validation.occurredAtTextReconciles("2026-01-15T00:31:00Z", occurredAt))
        // Garbage text with a missing draft must NOT reconcile.
        assertFalse(validation.occurredAtTextReconciles("not-a-time", null))
    }

    // D-138: Continue 门换用 lenient 解析器后，(a)–(d) 全格式文本重解析与 draft instant
    // 逐值相等才放行（规格 §2.3；P503IMPL-Q-001 不变量保持）。
    @Test
    fun occurredAtTextReconcilesAcrossAllLenientFormats() {
        val expected = Instant.parse("2026-09-15T00:30:00Z")
        // (a) ISO instant：含秒与无秒（选择器产物恒为整分钟 ISO 串，ISO superset）。
        assertTrue(validation.occurredAtTextReconciles("2026-09-15T00:30:00Z", expected))
        assertTrue(validation.occurredAtTextReconciles("2026-09-15T00:30Z", expected))
        // (b) 空格/T 分隔墙钟，单位数小时与秒可选。
        assertTrue(validation.occurredAtTextReconciles("2026-09-15 08:30", expected))
        assertTrue(validation.occurredAtTextReconciles("2026-09-15T08:30", expected))
        assertTrue(validation.occurredAtTextReconciles("2026-09-15 8:30", expected))
        assertTrue(validation.occurredAtTextReconciles("2026-09-15 08:30:15", Instant.parse("2026-09-15T00:30:15Z")))
        // (c) 省年格式按固定时钟补年为 2026。
        assertTrue(validation.occurredAtTextReconciles("09-15 20:00", Instant.parse("2026-09-15T12:00:00Z")))
        assertTrue(validation.occurredAtTextReconciles("09-15", Instant.parse("2026-09-14T16:00:00Z")))
        // (d) 仅日期 → 本地 00:00。
        assertTrue(validation.occurredAtTextReconciles("2026-09-15", Instant.parse("2026-09-14T16:00:00Z")))
    }

    // D-138: 相对表达与其他无效文本一律拦截（用户裁决 + 规格 §2.2 拒绝向量；draft 为
    // null 或非空同样拦截——门以当前文本重解析）。
    @Test
    fun occurredAtTextReconcilesRejectsRelativeAndInvalidText() {
        val drafts = listOf<Instant?>(null, Instant.parse("2026-09-15T00:30:00Z"))
        val texts =
            listOf(
                "昨天 20:00",
                "明天 09:00",
                "上周三 12:00",
                "上周",
                "2026-09-15T8:30Z",
                "2026-09-15 08:30Z",
                "2026-02-30",
                "2026-09-15  08:30",
                // 历史 DST 空档（fail-closed，规格 §2.1）。
                "1986-05-04 02:30",
            )
        for (text in texts) {
            for (draft in drafts) {
                assertFalse(validation.occurredAtTextReconciles(text, draft), "for input $text with draft $draft")
            }
        }
    }

    // D-138: 空白文本仅当 draft instant 为 null 时放行（既有 P503IMPL-Q-001 语义，
    // 规格 §2.3 分支 1）。
    @Test
    fun blankOccurredAtTextReconcilesOnlyToAMissingDraft() {
        assertTrue(validation.occurredAtTextReconciles("", null))
        assertTrue(validation.occurredAtTextReconciles("   ", null))
        assertFalse(validation.occurredAtTextReconciles("", occurredAt))
        assertFalse(validation.occurredAtTextReconciles("   ", occurredAt))
    }

    @Test
    fun isValidUsesTheSuppliedParseCurrency() {
        val draft =
            ManualExpenseDraft(
                paymentAccountId = AccountId("asset-payment-local"),
                categoryId = CategoryId("expense-category-breakfast"),
                amountText = "35.80",
                occurredAt = occurredAt,
            )

        // Valid under the selected account's CNY precision 2.
        assertTrue(validation.isValid(draft, CurrencyUnit("CNY", 2)))
        // "35.80" is an invalid amount under a zero-precision currency.
        assertFalse(validation.isValid(draft, CurrencyUnit("JPY", 0)))
    }

    // D-131 R1: lenient amounts are valid under their currency precision and lenient
    // rejections still carry the amount-format error (spec 2.6).
    @Test
    fun lenientAmountsAreValidUnderCnyAndJpy() {
        val cny = CurrencyUnit("CNY", 2)
        val jpy = CurrencyUnit("JPY", 0)

        assertTrue(validation.isValid(amountDraft("35.8"), cny))
        assertTrue(validation.isValid(amountDraft("11"), cny))
        assertTrue(validation.isValid(amountDraft("358.0"), jpy))
        assertTrue(validation.isValid(amountDraft("35.00"), jpy))
    }

    @Test
    fun lenientRejectionsStillCarryTheAmountFormatError() {
        val cny = CurrencyUnit("CNY", 2)
        assertTrue(validation.errors(amountDraft("35.812"), cny).amountFormatError != null)
        assertTrue(validation.errors(amountDraft("011"), cny).amountFormatError != null)
    }

    private fun amountDraft(amountText: String): ManualExpenseDraft =
        ManualExpenseDraft(
            paymentAccountId = AccountId("asset-payment-local"),
            categoryId = CategoryId("expense-category-breakfast"),
            amountText = amountText,
            occurredAt = occurredAt,
        )
}
