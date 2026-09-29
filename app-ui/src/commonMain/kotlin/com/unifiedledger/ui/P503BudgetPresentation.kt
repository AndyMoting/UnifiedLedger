package com.unifiedledger.ui

import com.unifiedledger.application.BudgetCommandResult
import com.unifiedledger.application.BudgetMonthResult
import com.unifiedledger.application.BudgetMonthViewResult
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.domain.BudgetScope
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit

/*
 * P7-07 07.D budget presentation decisions (D-184; spec sections 3.2/3.3/4/5). Every
 * load-bearing copy decision is a pure function here so it is covered by the app-ui JVM
 * tests (the P503LedgerViewPresentation precedent). Amounts go through [formatMinorUnits]
 * with the sign preserved and are formatted ONCE per row: the visible text and the row's
 * contentDescription share the exact same string (同源朗读， the C04 precedent). A configured
 * budget whose current revision is CLOSED is presented as 未监控 — never hidden, never a
 * zero — and every failure state has explicit copy that never renders a zero execution
 * amount (R-Q06-4 discipline).
 */

/** The frozen independent-observation declaration the region always shows (spec section 3.2). */
internal const val BUDGET_INDEPENDENT_OBSERVATION_NOTICE: String =
    "总预算、分类预算独立观察：各自计算各自的使用与超支，不互相相加或抵扣；额度按上海自然月、固定 CNY。"

/** The region headline for any landed state; failures are explicit and never amounts. */
internal fun budgetRegionHeadline(view: BudgetMonthViewResult?): String =
    when (view) {
        null -> "预算数据未加载。"
        BudgetMonthViewResult.InvalidState -> "预算数据不一致，无法展示（分类或数据异常）。"
        BudgetMonthViewResult.Unavailable -> "预算数据不可用（本地数据库读取失败）。"
        is BudgetMonthViewResult.Success -> "预算（${view.view.month}）"
    }

/**
 * The exact row texts of one landed month view, presentation-ordered (TOTAL first — the view
 * already orders it so — then the configured category scopes). A month with no configured
 * scope reads as one explicit empty statement, never as a zero row.
 */
internal fun budgetRegionRowsText(
    view: BudgetMonthViewResult.Success,
    categoryName: (CategoryId) -> String?,
): List<String> {
    if (view.view.observations.isEmpty()) return listOf("该月没有已配置的预算。")
    return view.view.observations.map { observation -> budgetObservationRowText(observation, categoryName) }
}

/**
 * One observation's exact text. A `null` limit means the budget is configured but its
 * current revision is CLOSED (a monitored budget always stores a non-null limit — zero is a
 * real `0`), so it reads 未监控 instead of rendering a zero usage line.
 */
internal fun budgetObservationRowText(
    observation: BudgetMonthResult.Success,
    categoryName: (CategoryId) -> String?,
): String {
    val budget = observation.budgetMonth
    val label = budgetScopeLabel(budget.scope, categoryName)
    val limit = budget.limitMinorUnits
    if (limit == null) return "$label：未监控（已配置但已关闭监控）"
    val precision = budget.currency.precision
    val used = formatMinorUnits(budget.netExpenseMinorUnits, precision)
    val remaining =
        budget.remainingMinorUnits?.let { formatMinorUnits(it, precision) }.orEmpty()
    val overspent = budget.overspentMinorUnits ?: 0L
    val base = "$label：已用 $used，剩余 $remaining"
    return if (overspent > 0L) "$base，超支 ${formatMinorUnits(overspent, precision)}" else base
}

/** Scope display label; a name missing from the catalog falls back to the stable id, never blank. */
internal fun budgetScopeLabel(
    scope: BudgetScope,
    categoryName: (CategoryId) -> String?,
): String =
    when (scope) {
        BudgetScope.Total -> "总预算"
        is BudgetScope.Category -> {
            val name = categoryName(scope.categoryId)
            if (name.isNullOrEmpty() || name.isBlank()) "分类 " + scope.categoryId.value else name
        }
    }

/** The config dialog's status line: unset / closed / monitored are the three distinct states. */
internal fun budgetConfigStatusText(
    revision: Long,
    closed: Boolean,
    limitMinorUnits: Long?,
    currency: CurrencyUnit,
): String =
    when {
        revision == 0L -> "未设置（不监控）"
        closed || limitMinorUnits == null -> "已关闭监控（保留历史）"
        else -> "监控中：当前额度 " + formatMinorUnits(requireNotNull(limitMinorUnits), currency.precision)
    }

/** The landed command outcome banner; every family gets its own explicit copy. */
internal fun budgetConfigNoticeText(result: BudgetCommandResult): String =
    when (result) {
        is BudgetCommandResult.Accepted -> "已保存（新 revision ${result.receipt.newRevision}）。"
        is BudgetCommandResult.NoChange -> "已确认（重复请求，返回原回执）。"
        is BudgetCommandResult.Rejected -> "未能保存：${result.failureCode.code}。"
        is BudgetCommandResult.Conflict -> "保存冲突：${result.failureCode.code}（请重试）。"
    }

/** The typed limit-field draft: a valid non-negative minor amount, or [BudgetLimitDraft.Invalid]. */
internal sealed interface BudgetLimitDraft {
    data class Valid(
        val minorUnits: Long,
    ) : BudgetLimitDraft

    data object Invalid : BudgetLimitDraft
}

/**
 * Parses the config dialog's limit text through the SAME [ParseManualExpenseAmount] the entry
 * flow uses (同源): blank, malformed, whitespace-containing or negative text is an invalid
 * draft (the confirm affordance is disabled, so no commit is ever attempted from one).
 */
internal fun budgetLimitDraft(
    text: String,
    currency: CurrencyUnit,
): BudgetLimitDraft {
    if (text.isBlank()) return BudgetLimitDraft.Invalid
    return when (val parsed = ParseManualExpenseAmount().parse(text, currency)) {
        is ParseManualExpenseAmount.Result.Valid ->
            if (parsed.minorUnits < 0L) BudgetLimitDraft.Invalid else BudgetLimitDraft.Valid(parsed.minorUnits)
        is ParseManualExpenseAmount.Result.Invalid -> BudgetLimitDraft.Invalid
    }
}
