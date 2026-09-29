package com.unifiedledger.ui

import com.unifiedledger.application.BudgetCommandReceipt
import com.unifiedledger.application.BudgetCommandResult
import com.unifiedledger.application.BudgetFailureCode
import com.unifiedledger.application.BudgetMonthResult
import com.unifiedledger.application.BudgetMonthView
import com.unifiedledger.application.BudgetMonthViewResult
import com.unifiedledger.application.BudgetReceiptOutcome
import com.unifiedledger.domain.BudgetId
import com.unifiedledger.domain.BudgetScope
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P7-07 07.D budget presentation evidence (D-184; spec sections 3.2/3.3/4/5): the region
 * always states 独立观察， amounts are the EXACT formatted minor units shared by the visible
 * text and the TalkBack label (one source), a configured-but-closed scope reads 未监控
 * (never hidden, never zero), failure states have explicit copy that never renders a zero
 * execution amount, and the config dialog's status/notice/draft decisions are typed.
 */
class P503BudgetPresentationTest {
    private val ledgerId = LedgerId("ledger-budget-presentation")
    private val cny = CurrencyUnit("CNY", 2)
    private val march = YearMonth(2026, 3)
    private val breakfastId = CategoryId("category-breakfast")

    private fun observation(
        scope: BudgetScope,
        limit: Long?,
        net: Long,
    ): BudgetMonthResult.Success {
        val remaining = limit?.let { limit - net }
        val overspent = limit?.let { if (net - limit > 0L) net - limit else 0L }
        return BudgetMonthResult.Success(
            com.unifiedledger.application.BudgetMonth(
                ledgerId = ledgerId,
                month = march,
                currency = cny,
                scope = scope,
                limitMinorUnits = limit,
                netExpenseMinorUnits = net,
                remainingMinorUnits = remaining,
                overspentMinorUnits = overspent,
            ),
        )
    }

    @Test
    fun theRegionAlwaysStatesTheIndependentObservationRule() {
        assertTrue(BUDGET_INDEPENDENT_OBSERVATION_NOTICE.contains("独立观察"))
        assertTrue(BUDGET_INDEPENDENT_OBSERVATION_NOTICE.contains("总预算"))
        assertTrue(BUDGET_INDEPENDENT_OBSERVATION_NOTICE.contains("分类预算"))
    }

    @Test
    fun observationRowsReadExactSignedAmountsForVisibleTextAndTalkBackAlike() {
        val text =
            budgetObservationRowText(
                observation(BudgetScope.Total, limit = 100_00L, net = 120_00L),
                categoryName = { null },
            )
        // One formatted source: the same string is the visible row text and the row's
        // contentDescription, so TalkBack reads the exact value it sees (同源朗读).
        assertTrue(text.contains("已用 120.00"), text)
        assertTrue(text.contains("剩余 -20.00"), text)
        assertTrue(text.contains("超支 20.00"), text)
        assertTrue(text.contains("总预算"), text)
        // Boundary: exactly at the limit is NOT overspent (spec section 4).
        val boundary =
            budgetObservationRowText(observation(BudgetScope.Total, limit = 50_00L, net = 50_00L), categoryName = { null })
        assertFalse(boundary.contains("超支"), boundary)
        assertTrue(boundary.contains("剩余 0.00"), boundary)
    }

    @Test
    fun aClosedScopeReadsUnmonitoredAndANetRefundKeepsItsSign() {
        val closed = budgetObservationRowText(observation(BudgetScope.Total, limit = null, net = 5_00L), categoryName = { null })
        assertTrue(closed.contains("未监控"), closed)
        assertFalse(closed.contains("已用"), closed)
        val refund =
            budgetObservationRowText(
                observation(BudgetScope.Category(breakfastId), limit = 10_00L, net = -3_00L),
                categoryName = { id -> if (id == breakfastId) "早餐" else null },
            )
        assertTrue(refund.contains("早餐"), refund)
        assertTrue(refund.contains("已用 -3.00"), refund)
        assertTrue(refund.contains("剩余 13.00"), refund)
    }

    @Test
    fun scopeLabelsUseCurrentCatalogNamesWithAnHonestFallback() {
        assertEquals("总预算", budgetScopeLabel(BudgetScope.Total) { null })
        assertEquals("早餐", budgetScopeLabel(BudgetScope.Category(breakfastId)) { if (it == breakfastId) "早餐" else null })
        assertEquals("分类 category-gone", budgetScopeLabel(BudgetScope.Category(CategoryId("category-gone"))) { null })
    }

    @Test
    fun failureStatesHaveExplicitCopyAndNeverRenderZeroAmounts() {
        assertNullViewChecks(BudgetMonthViewResult.Unavailable, "不可用")
        assertNullViewChecks(BudgetMonthViewResult.InvalidState, "不一致")
        assertNullViewChecks(null, "未加载")
        val emptySuccess = BudgetMonthViewResult.Success(BudgetMonthView(ledgerId, march, catalogVersion = 1L, total = null, categories = emptyList()))
        // A month with NO configured scope is an explicit empty statement, not a zero row.
        assertEquals("该月没有已配置的预算。", budgetRegionRowsText(emptySuccess, categoryName = { null }).single())
    }

    private fun assertNullViewChecks(
        view: BudgetMonthViewResult?,
        expectedFragment: String,
    ) {
        val headline = budgetRegionHeadline(view)
        assertTrue(headline.contains(expectedFragment), headline)
        assertFalse(headline.contains("已用"), headline)
    }

    @Test
    fun configStatusTextDistinguishesUnsetClosedAndMonitored() {
        assertEquals("未设置（不监控）", budgetConfigStatusText(revision = 0L, closed = false, limitMinorUnits = null, currency = cny))
        assertEquals("已关闭监控（保留历史）", budgetConfigStatusText(revision = 3L, closed = true, limitMinorUnits = null, currency = cny))
        assertEquals("监控中：当前额度 100.00", budgetConfigStatusText(revision = 2L, closed = false, limitMinorUnits = 100_00L, currency = cny))
    }

    @Test
    fun configNoticeTextCoversEveryOutcomeFamily() {
        val receipt =
            BudgetCommandReceipt(
                requestId = com.unifiedledger.application.BudgetRequestId("r"),
                outcome = BudgetReceiptOutcome.ACCEPTED,
                budgetId = BudgetId("b"),
                newRevision = 2L,
            )
        assertTrue(budgetConfigNoticeText(BudgetCommandResult.Accepted(receipt)).contains("已保存"))
        assertTrue(budgetConfigNoticeText(BudgetCommandResult.NoChange(receipt)).contains("重复"))
        assertTrue(budgetConfigNoticeText(BudgetCommandResult.Rejected(BudgetFailureCode.BUDGET_LIMIT_NEGATIVE)).contains("BudgetLimitNegative"))
        assertTrue(budgetConfigNoticeText(BudgetCommandResult.Conflict(BudgetFailureCode.BUDGET_REVISION_CONFLICT)).contains("BudgetRevisionConflict"))
    }

    @Test
    fun limitDraftTypingIsExactAndNeverFloaty() {
        assertEquals(BudgetLimitDraft.Valid(5_000L), budgetLimitDraft("50", cny))
        assertEquals(BudgetLimitDraft.Valid(5_050L), budgetLimitDraft("50.5", cny))
        assertEquals(BudgetLimitDraft.Valid(0L), budgetLimitDraft("0", cny))
        assertEquals(BudgetLimitDraft.Invalid, budgetLimitDraft("-1", cny))
        assertEquals(BudgetLimitDraft.Invalid, budgetLimitDraft("abc", cny))
        assertEquals(BudgetLimitDraft.Invalid, budgetLimitDraft("", cny))
        assertEquals(BudgetLimitDraft.Invalid, budgetLimitDraft("1 0", cny))
    }
}
