package com.unifiedledger.ui

import com.unifiedledger.application.CollectDraft
import com.unifiedledger.application.ExpenseDraft
import com.unifiedledger.application.IncomeDraft
import com.unifiedledger.application.LendDraft
import com.unifiedledger.application.TransferDraft
import com.unifiedledger.application.TypedEntryDraft
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CounterpartyId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A-02 FIX-CONFIRM-1: the type-aware confirmation page (spec section 4). The page is a pure
 * function pair, so every type's title and field set is pinned here without a Compose harness —
 * including the two negative contracts that the defect turned on: no type may label a counterparty
 * or an interest/fee category as 费用分类, and a transfer must show the destination credit and the
 * total debit side by side rather than one ambiguous amount.
 */
class P503ConfirmationPresentationTest {
    private val currencyCode = "CNY"
    private val currencyPrecision = 2
    private val occurredAt = Instant.parse("2026-01-15T00:30:00Z")
    private val accountId = AccountId("asset-a")
    private val otherAccountId = AccountId("asset-b")
    private val categoryId = CategoryId("expense-cat")
    private val counterpartyId = CounterpartyId("counterparty-a")

    private val labels =
        ConfirmationLabels(
            paymentAccount = "本地账户",
            category = "餐饮",
            destinationAccount = "对方账户",
            counterparty = "张三",
        )

    private fun labelsOf(rows: List<ConfirmationRow>): List<String> = rows.map { it.label }

    private fun valueOf(
        rows: List<ConfirmationRow>,
        label: String,
    ): String = rows.first { it.label == label }.value

    // ---- titles (spec 4.3 item 1) ----

    @Test
    fun confirmationTitleIsTypeAware() {
        assertEquals("确认支出", confirmationTitle(expense()))
        assertEquals("确认收入", confirmationTitle(income()))
        assertEquals("确认转账", confirmationTitle(transfer("100.00", "2.50")))
        assertEquals("确认借出", confirmationTitle(lend()))
        assertEquals("确认收回", confirmationTitle(collect()))
    }

    // ---- expense / income copy (spec 4.3 item 6) ----

    @Test
    fun expenseAndIncomeRowsKeepTheExistingCopy() {
        val expenseRows = confirmationRows(expense(), labels, currencyCode, currencyPrecision)
        assertEquals(listOf("支付账户", "费用分类", "金额"), labelsOf(expenseRows))
        assertEquals("本地账户", valueOf(expenseRows, "支付账户"))
        assertEquals("餐饮", valueOf(expenseRows, "费用分类"))
        assertEquals("35.80 $currencyCode", valueOf(expenseRows, "金额"))

        val incomeRows = confirmationRows(income(), labels, currencyCode, currencyPrecision)
        assertEquals(listOf("收款账户", "收入分类", "金额"), labelsOf(incomeRows))
        assertEquals("本地账户", valueOf(incomeRows, "收款账户"))
        assertEquals("餐饮", valueOf(incomeRows, "收入分类"))
        assertEquals("300.00 $currencyCode", valueOf(incomeRows, "金额"))
    }

    // ---- transfer (spec 4.3 items 2/3) ----

    @Test
    fun transferWithAFeeShowsBothTheCreditAndTheTotalDebit() {
        val rows = confirmationRows(transfer("100.00", "2.50"), labels, currencyCode, currencyPrecision)
        assertEquals(listOf("转出账户", "转入账户", "到账本金", "手续费", "手续费分类", "转出总额"), labelsOf(rows))
        assertEquals("本地账户", valueOf(rows, "转出账户"))
        assertEquals("对方账户", valueOf(rows, "转入账户"))
        assertEquals("100.00 $currencyCode", valueOf(rows, "到账本金"))
        assertEquals("2.50 $currencyCode", valueOf(rows, "手续费"))
        assertEquals("餐饮", valueOf(rows, "手续费分类"))
        assertEquals("102.50 $currencyCode", valueOf(rows, "转出总额"))
        assertFalse(labelsOf(rows).contains("费用分类"), "a transfer fee category is never a 费用分类")
    }

    @Test
    fun transferWithoutAFeeOmitsTheFeeCategoryAndTotalsTheCredit() {
        val rows = confirmationRows(transfer("100.00", "0.00"), labels, currencyCode, currencyPrecision)
        assertEquals(listOf("转出账户", "转入账户", "到账本金", "手续费", "转出总额"), labelsOf(rows))
        assertFalse(labelsOf(rows).contains("手续费分类"), "a zero fee never carries a fee category")
        assertEquals(valueOf(rows, "到账本金"), valueOf(rows, "转出总额"))
    }

    @Test
    fun transferWithABlankFeeShowsTheCreditAsTheTotalDebit() {
        // The gate accepts a blank fee and the submission path stores it as zero, so the page
        // shows the fee as absent and the total debit as the credit alone rather than a bare
        // currency code or a missing total.
        val rows = confirmationRows(transfer("100.00", ""), labels, currencyCode, currencyPrecision)
        assertEquals(listOf("转出账户", "转入账户", "到账本金", "手续费", "转出总额"), labelsOf(rows))
        assertEquals("—", valueOf(rows, "手续费"))
        assertEquals("100.00 $currencyCode", valueOf(rows, "转出总额"))
        assertFalse(labelsOf(rows).contains("手续费分类"), "a blank fee never carries a fee category")
    }

    @Test
    fun transferWithAnExcessZeroFractionRendersTheTotalAtTheCurrencyScale() {
        // The gate accepts fraction digits beyond the currency precision when every excess digit
        // is zero, so the derived total is rendered at the currency scale, not the text's scale.
        val rows = confirmationRows(transfer("100.00", "2.500"), labels, currencyCode, currencyPrecision)
        assertEquals("102.50 $currencyCode", valueOf(rows, "转出总额"))
        // Component rows keep the draft's typed text verbatim (spec 4.3 item 7).
        assertEquals("2.500 $currencyCode", valueOf(rows, "手续费"))
    }

    @Test
    fun anAllZeroLongFractionKeepsTheFeeCategoryAndTheCurrencyScaleTotal() {
        val rows = confirmationRows(transfer("100.00", "2.5000000000000000000"), labels, currencyCode, currencyPrecision)
        assertEquals(listOf("转出账户", "转入账户", "到账本金", "手续费", "手续费分类", "转出总额"), labelsOf(rows))
        assertEquals("102.50 $currencyCode", valueOf(rows, "转出总额"))
    }

    // ---- lend / collect (spec 4.3 items 4/5) ----

    @Test
    fun lendShowsTheCounterpartyAndTheFundingAccount() {
        val rows = confirmationRows(lend(), labels, currencyCode, currencyPrecision)
        assertEquals(listOf("往来对象", "出资账户", "借出金额"), labelsOf(rows))
        assertEquals("张三", valueOf(rows, "往来对象"))
        assertEquals("本地账户", valueOf(rows, "出资账户"))
        assertEquals("100.00 $currencyCode", valueOf(rows, "借出金额"))
        assertFalse(labelsOf(rows).contains("费用分类"), "a lending counterparty is never a 费用分类")
    }

    @Test
    fun collectShowsThePrincipalInterestCompositionAndTheCarriedTotal() {
        val rows = confirmationRows(collect(), labels, currencyCode, currencyPrecision)
        assertEquals(listOf("往来对象", "到账账户", "本金", "利息", "利息分类", "实收总额"), labelsOf(rows))
        assertEquals("张三", valueOf(rows, "往来对象"))
        assertEquals("本地账户", valueOf(rows, "到账账户"))
        assertEquals("40.00 $currencyCode", valueOf(rows, "本金"))
        assertEquals("5.00 $currencyCode", valueOf(rows, "利息"))
        assertEquals("餐饮", valueOf(rows, "利息分类"))
        // The draft carries the collected total; the page renders it, never recomputes it.
        assertEquals("45.00 $currencyCode", valueOf(rows, "实收总额"))
        assertFalse(labelsOf(rows).contains("费用分类"), "an interest category is never a 费用分类")
    }

    @Test
    fun collectWithoutInterestOmitsTheInterestCategory() {
        val rows = confirmationRows(collect(interest = "0.00", totalReceived = "40.00"), labels, currencyCode, currencyPrecision)
        assertFalse(labelsOf(rows).contains("利息分类"), "a zero interest never carries an interest category")
        assertEquals("40.00 $currencyCode", valueOf(rows, "实收总额"))
    }

    @Test
    fun collectWithBlankComponentsRendersTheAbsentPlaceholder() {
        val rows =
            confirmationRows(
                collect(principal = "", interest = "", totalReceived = "40.00"),
                labels,
                currencyCode,
                currencyPrecision,
            )
        assertEquals("—", valueOf(rows, "本金"))
        assertEquals("—", valueOf(rows, "利息"))
        assertEquals("40.00 $currencyCode", valueOf(rows, "实收总额"))
        assertFalse(labelsOf(rows).contains("利息分类"), "a blank interest never carries an interest category")
    }

    @Test
    fun rowsRenderTheAbsentPlaceholderForAMissingTypeOwnedLabel() {
        val bare = labels.copy(destinationAccount = null, counterparty = null)
        assertEquals("—", valueOf(confirmationRows(transfer("100.00", "2.50"), bare, currencyCode, currencyPrecision), "转入账户"))
        assertEquals("—", valueOf(confirmationRows(lend(), bare, currencyCode, currencyPrecision), "往来对象"))
        assertEquals("—", valueOf(confirmationRows(collect(), bare, currencyCode, currencyPrecision), "往来对象"))
    }

    // ---- exact decimal (spec 4.3 item 7) ----

    @Test
    fun transferTotalIsExactDecimalArithmetic() {
        // 0.10 + 0.20 is the classic binary-floating-point counterexample; the total must be 0.30.
        assertEquals("0.30 $currencyCode", valueOf(confirmationRows(transfer("0.10", "0.20"), labels, currencyCode, currencyPrecision), "转出总额"))
        assertEquals("100.00 $currencyCode", valueOf(confirmationRows(transfer("99.99", "0.01"), labels, currencyCode, currencyPrecision), "转出总额"))
        // A short fraction stays exact: the total renders at the currency scale, never rounded.
        assertEquals("102.50 $currencyCode", valueOf(confirmationRows(transfer("100", "2.5"), labels, currencyCode, currencyPrecision), "转出总额"))
    }

    @Test
    fun thePageNeverEvaluatesAnExpression() {
        // The page renders the draft's text; it never evaluates it (the Continue gate is the only
        // amount validator). A non-decimal text is therefore shown verbatim and contributes no
        // derived total rather than being computed.
        val rows = confirmationRows(transfer("1/8", "0.00"), labels, currencyCode, currencyPrecision)
        assertEquals("1/8 $currencyCode", valueOf(rows, "到账本金"))
        assertEquals("—", valueOf(rows, "转出总额"))
    }

    @Test
    fun everyAmountRowOfATwoDecimalTransferRendersTwoDecimals() {
        val rows = confirmationRows(transfer("100.00", "2.50"), labels, currencyCode, currencyPrecision)
        for (label in listOf("到账本金", "手续费", "转出总额")) {
            val value = valueOf(rows, label).removeSuffix(" $currencyCode")
            assertTrue(value.matches(Regex("^[0-9]+\\.[0-9]{2}$")), "$label is not exact two-decimal text: $value")
        }
    }

    // ---- fixtures ----

    private fun expense(): TypedEntryDraft = ExpenseDraft(accountId, categoryId, "35.80", occurredAt)

    private fun income(): TypedEntryDraft = IncomeDraft(accountId, categoryId, "300.00", occurredAt)

    private fun transfer(
        destinationCredit: String,
        fee: String,
    ): TypedEntryDraft =
        TransferDraft(
            sourceAccountId = accountId,
            destinationAccountId = otherAccountId,
            destinationCredit = destinationCredit,
            fee = fee,
            feeCategoryId = categoryId,
            occurredAt = occurredAt,
        )

    private fun lend(): TypedEntryDraft = LendDraft(counterpartyId, "100.00", accountId, occurredAt)

    private fun collect(
        principal: String = "40.00",
        interest: String = "5.00",
        totalReceived: String = "45.00",
    ): TypedEntryDraft =
        CollectDraft(
            counterpartyId = counterpartyId,
            totalReceived = totalReceived,
            principal = principal,
            interest = interest,
            interestCategoryId = categoryId,
            destinationAccountId = accountId,
            occurredAt = occurredAt,
        )
}
