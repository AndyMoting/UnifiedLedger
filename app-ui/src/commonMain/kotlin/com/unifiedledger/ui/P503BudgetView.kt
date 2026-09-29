package com.unifiedledger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.unifiedledger.application.BudgetCommandResult
import com.unifiedledger.application.BudgetMonthResult
import com.unifiedledger.application.BudgetMonthViewResult
import com.unifiedledger.domain.BudgetScope
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit

/*
 * P7-07 07.D budget presentations (D-184; spec sections 3.2/3.3/4/5). The region is an
 * ADDITIVE surface on the analysis month view: a typed read failure renders the explicit
 * failure copy inside the region and never displaces the overview, never renders a zero
 * execution amount. Every amount string comes from [budgetRegionRowsText] /
 * [budgetObservationRowText], and the SAME string is the row's contentDescription (同源朗读，
 * the C04 precedent). A configured-but-closed budget reads 未监控 — never hidden, never zero —
 * and unconfigured scopes are simply absent (no invented row). The config surface follows the
 * TransactionEdit/BackupExport preserved-overview discipline with the P7-05 submitting
 * markers (提交中不重入/不得离开); the async outcome banner carries liveRegion = Assertive so
 * TalkBack announces it when it lands.
 */

/** One picker choice of the 新增分类预算 dialog: a scope plus its current display name. */
internal data class BudgetCategoryChoice(
    val scope: BudgetScope,
    val label: String,
)

/**
 * The budget region on the analysis month view. [view] is the last landed read (null = not
 * loaded yet); [categoryName] resolves current catalog names (an honest stable-id fallback);
 * [parentIdOf] resolves the level-1/level-2 nesting (configured level-2 scopes of a configured
 * level-1 parent nest under it via the [P503CategoryRegion] expandedIds pattern); the picker
 * lists the configurable category scopes of the 新增分类预算 affordance.
 */
@Composable
internal fun P503BudgetRegion(
    view: BudgetMonthViewResult?,
    categoryName: (CategoryId) -> String?,
    parentIdOf: (CategoryId) -> CategoryId?,
    pickerCategories: List<BudgetCategoryChoice>,
    onOpenBudgetConfig: (BudgetScope) -> Unit,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("预算", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        // The frozen independent-observation declaration (spec section 3.2) is always shown.
        Text(BUDGET_INDEPENDENT_OBSERVATION_NOTICE, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        val failureColor =
            when (view) {
                BudgetMonthViewResult.InvalidState, BudgetMonthViewResult.Unavailable -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            }
        // The headline is the explicit state line (loading / failure / month) and is announced
        // when it lands (the async read lands off the UI thread).
        Text(
            budgetRegionHeadline(view),
            style = MaterialTheme.typography.bodyMedium,
            color = failureColor,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
        if (view is BudgetMonthViewResult.Success) {
            Spacer(Modifier.height(4.dp))
            P503BudgetObservationRows(view, categoryName, parentIdOf, onOpenBudgetConfig)
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = { pickerOpen = true }, enabled = pickerCategories.isNotEmpty()) {
            Text("新增分类预算")
        }
        TextButton(onClick = { onOpenBudgetConfig(BudgetScope.Total) }) {
            Text(if (hasTotalRow(view)) "修改总预算" else "设置总预算")
        }
    }
    if (pickerOpen) {
        P503BudgetScopePickerDialog(
            choices = pickerCategories,
            onDismiss = { pickerOpen = false },
            onPick = { scope ->
                pickerOpen = false
                onOpenBudgetConfig(scope)
            },
        )
    }
}

private fun hasTotalRow(view: BudgetMonthViewResult?): Boolean = view is BudgetMonthViewResult.Success && view.view.total != null

/**
 * The observation rows: the TOTAL row first, then the configured category scopes; a level-2
 * scope whose level-1 parent is also configured nests under it (expand/collapse, the
 * [P503CategoryRegion] expandedIds pattern). Each row's contentDescription is the SAME exact
 * text the row displays (同源朗读) and carries one 设置/修改额度 affordance.
 */
@Composable
private fun P503BudgetObservationRows(
    view: BudgetMonthViewResult.Success,
    categoryName: (CategoryId) -> String?,
    parentIdOf: (CategoryId) -> CategoryId?,
    onOpenBudgetConfig: (BudgetScope) -> Unit,
) {
    val observations = view.view.observations
    var expandedIds by remember(view) { mutableStateOf(emptySet<CategoryId>()) }
    // Level split: a scope is level-2 exactly when its catalog parent exists and that parent
    // also carries a configured row here (otherwise it renders as a top-level row).
    val configuredCategoryIds = observations.mapNotNull { (it.budgetMonth.scope as? BudgetScope.Category)?.categoryId }.toSet()
    val topLevel = ArrayList<BudgetMonthResult.Success>()
    val childrenByParent = LinkedHashMap<CategoryId, MutableList<BudgetMonthResult.Success>>()
    for (observation in observations) {
        val scope = observation.budgetMonth.scope
        val categoryId = (scope as? BudgetScope.Category)?.categoryId
        val parent = categoryId?.let(parentIdOf)
        if (categoryId == null || parent == null || parent !in configuredCategoryIds) {
            topLevel.add(observation)
        } else {
            childrenByParent.getOrPut(parent) { mutableListOf() }.add(observation)
        }
    }
    topLevel.forEach { observation ->
        val scope = observation.budgetMonth.scope
        val categoryId = (scope as? BudgetScope.Category)?.categoryId
        val children = categoryId?.let(childrenByParent::get).orEmpty()
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(
                            enabled = children.isNotEmpty(),
                            onClickLabel = "展开或收起${budgetScopeLabel(scope, categoryName)}的分类子预算",
                        ) {
                            if (categoryId != null) {
                                expandedIds =
                                    if (categoryId in expandedIds) expandedIds - categoryId else expandedIds + categoryId
                            }
                        }
                        // C04: the interactive row announces its exact amounts — the same string
                        // the row renders (同源朗读).
                        .semantics(mergeDescendants = true) {
                            contentDescription = budgetObservationRowText(observation, categoryName)
                        },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (children.isNotEmpty()) {
                    Text(if (categoryId != null && categoryId in expandedIds) "▾" else "▸", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(4.dp))
                }
                Text(budgetObservationRowText(observation, categoryName), style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onOpenBudgetConfig(scope) }) {
                    Text(if (observation.budgetMonth.limitMinorUnits == null) "设置额度" else "修改额度")
                }
            }
            if (categoryId != null && categoryId in expandedIds) {
                children.forEach { child ->
                    Column(modifier = Modifier.padding(start = 20.dp)) {
                        Text(
                            budgetObservationRowText(child, categoryName),
                            style = MaterialTheme.typography.bodySmall,
                            modifier =
                                Modifier.semantics(mergeDescendants = true) {
                                    contentDescription = budgetObservationRowText(child, categoryName)
                                },
                        )
                        TextButton(onClick = { onOpenBudgetConfig(child.budgetMonth.scope) }) {
                            Text(if (child.budgetMonth.limitMinorUnits == null) "设置额度" else "修改额度")
                        }
                    }
                }
            }
        }
    }
}

/** The 新增分类预算 scope picker over the catalog's configurable category scopes. */
@Composable
private fun P503BudgetScopePickerDialog(
    choices: List<BudgetCategoryChoice>,
    onDismiss: () -> Unit,
    onPick: (BudgetScope) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择要配置预算的分类") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                choices.forEach { choice ->
                    Text(
                        choice.label,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(onClickLabel = "配置${choice.label}的预算") { onPick(choice.scope) }
                                .padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * The explicit budget-configuration surface (P7-07 07.D): status line (未设置/已关闭/监控中 with
 * the exact current limit), the limit field validated by [budgetLimitDraft] (the confirm
 * affordance is enabled exactly for a valid non-negative parse), the close-monitoring action
 * for an open budget, the typed outcome banner (liveRegion = Assertive) and the guarded exit.
 * 提交中不重入： while [P503AppState.BudgetConfig.submitting] the field and every affordance
 * disable and the exit is swallowed by the reducer.
 */
@Composable
internal fun P503BudgetConfigScreen(
    current: P503AppState.BudgetConfig,
    categoryName: (CategoryId) -> String?,
    currency: CurrencyUnit,
    onLimitTextChange: (String) -> Unit,
    onConfirmLimit: () -> Unit,
    onCloseMonitoring: () -> Unit,
    onDismiss: () -> Unit,
) {
    val draft = budgetLimitDraft(current.limitText, currency)
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        Text("预算配置", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "范围：" + budgetScopeLabel(current.scope, categoryName) + "，月份：" + current.month.toString(),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(BUDGET_INDEPENDENT_OBSERVATION_NOTICE, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        Text(
            budgetConfigStatusText(current.revision, current.closed, current.limitMinorUnits, currency),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        // A CLOSED budget keeps no editable limit (close is the current state; the history is
        // kept); an unset or monitored budget offers the save affordance.
        if (!current.closed) {
            OutlinedTextField(
                value = current.limitText,
                onValueChange = onLimitTextChange,
                enabled = !current.submitting,
                label = { Text("额度（精确金额）") },
                isError = current.limitText.isNotBlank() && draft is BudgetLimitDraft.Invalid,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Button(
                enabled = !current.submitting && draft is BudgetLimitDraft.Valid,
                onClick = onConfirmLimit,
            ) {
                Text("确认保存")
            }
        }
        if (current.revision > 0L && !current.closed) {
            TextButton(enabled = !current.submitting, onClick = onCloseMonitoring) {
                Text("关闭监控（保留配置与历史）")
            }
        }
        current.outcome?.let { outcome ->
            val accepted = outcome is BudgetCommandResult.Accepted || outcome is BudgetCommandResult.NoChange
            Text(
                budgetConfigNoticeText(outcome),
                style = MaterialTheme.typography.bodyMedium,
                color = if (accepted) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                modifier =
                    Modifier
                        .padding(top = 4.dp)
                        // The async result lands off the UI thread; announce it when it arrives
                        // (the P503App liveRegion precedent).
                        .semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        Spacer(Modifier.height(12.dp))
        TextButton(enabled = !current.submitting, onClick = onDismiss) {
            Text("返回")
        }
        // The month cursor is preserved by the carried overview; nothing here invents amounts.
        if (current.submitting) {
            Text("提交中…", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
    }
}
