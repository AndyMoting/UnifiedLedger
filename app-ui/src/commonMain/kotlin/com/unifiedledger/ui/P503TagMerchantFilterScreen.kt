package com.unifiedledger.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.unifiedledger.application.MerchantMonthlyAccumulationResult
import com.unifiedledger.application.TagMerchantFilterCondition
import com.unifiedledger.application.TagMerchantFilterFailureCode
import com.unifiedledger.application.TagMerchantTagMatchMode
import com.unifiedledger.application.foldTagMerchantGroups
import com.unifiedledger.application.tagMerchantGroupTransactionCount
import com.unifiedledger.domain.CatalogItem
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.MerchantId
import com.unifiedledger.domain.TagId

/*
 * P7-08 08.C (spec sections 5.1/5.3/5.4; open item 8): the tag/merchant filter surface. A thin
 * renderer like the P7-05/P7-07 screens — every affordance takes a callback the composition root
 * wires, and the screen never reads the facade.
 *
 * Condition pickers (spec section 5.1): tags are a MULTI-SELECT whose options come from the
 * facade's filter-options projection — DISABLED items included, tombstoned excluded — so a
 * disabled tag stays selectable for history queries while a tombstoned one (zero references by
 * construction) never appears. The OR/AND switch and the optional merchant radio follow.
 *
 * Results (spec sections 5.1/5.2): each selected tag's group shows ITS OWN full overlap total
 * (the pure Kotlin fold of the accumulated pages over the landed catalog snapshot) and the
 * declaration states the groups are never additive. A typed read failure renders the explicit
 * failure copy with a liveRegion (the P7-05 TalkBack lesson) and never an empty "no matches"
 * (spec section 5.3 fail-closed).
 *
 * The merchant accumulation block (spec section 5.4) renders the month's ordinary net expense
 * per currency with the 不含特殊消费 declaration (stored-value/prepaid are out of scope).
 */

/** The filter screen's condition + results presentation. */
@Composable
internal fun P503TagMerchantFilterScreen(
    state: P503AppState.TagMerchantFilter,
    tagOptions: List<CatalogItem>?,
    merchantOptions: List<CatalogItem>?,
    onToggleTag: (TagId) -> Unit,
    onSetTagMatch: (TagMerchantTagMatchMode) -> Unit,
    onSelectMerchant: (MerchantId?) -> Unit,
    onLoadMore: () -> Unit,
    onShiftMonth: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val condition = state.condition
    val groupTotals =
        remember(state.loadedEntries, condition.tagIds, state.entryCatalog) {
            state.entryCatalog?.let { catalog ->
                foldTagMerchantGroups(state.loadedEntries, condition.tagIds, state.overview.state.ledgerId, catalog)
            }
        }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("标签与商家筛选", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onBack) { Text("返回") }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "停用的标签仍可选择并命中历史标注；已删除的条目不会出现。",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))

        // ---- condition: tags (multi-select, disabled included / tombstoned excluded) ----
        Text("标签（可多选）", style = MaterialTheme.typography.titleSmall)
        when {
            tagOptions == null -> Text("标签目录载入中…", style = MaterialTheme.typography.bodySmall)
            tagOptions.isEmpty() -> Text("当前没有可筛选的标签。", style = MaterialTheme.typography.bodySmall)
            else ->
                tagOptions.forEach { tag ->
                    val id = TagId(tag.id)
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = id in condition.tagIds,
                            onCheckedChange = { onToggleTag(id) },
                        )
                        Text(tag.name + if (tag.active) "" else "（已停用）")
                    }
                }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = condition.tagMatch == TagMerchantTagMatchMode.Any,
                onClick = { onSetTagMatch(TagMerchantTagMatchMode.Any) },
                enabled = condition.tagIds.isNotEmpty(),
            )
            Text("任一标签（或）")
            Spacer(Modifier.padding(horizontal = 8.dp))
            RadioButton(
                selected = condition.tagMatch == TagMerchantTagMatchMode.All,
                onClick = { onSetTagMatch(TagMerchantTagMatchMode.All) },
                enabled = condition.tagIds.isNotEmpty(),
            )
            Text("全部标签（与）")
        }
        Spacer(Modifier.height(12.dp))
        Text("商家（可选）", style = MaterialTheme.typography.titleSmall)
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = condition.merchantId == null,
                onClick = { onSelectMerchant(null) },
            )
            Text("不关联商家")
        }
        if (merchantOptions == null) {
            Text("商家目录载入中…", style = MaterialTheme.typography.bodySmall)
        } else {
            merchantOptions.forEach { merchant ->
                val id = MerchantId(merchant.id)
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = condition.merchantId == id,
                        onClick = { onSelectMerchant(id) },
                    )
                    Text(merchant.name + if (merchant.active) "" else "（已停用）")
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        // ---- results: the matched set's per-tag groups ----
        Text("筛选结果", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        state.pageFailure?.let { failure ->
            val message = tagMerchantFilterFailureText(failure)
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier =
                    Modifier.semantics {
                        contentDescription = message
                        liveRegion = LiveRegionMode.Polite
                    },
            )
            Spacer(Modifier.height(4.dp))
        }
        if (condition.tagIds.isEmpty()) {
            Text("未选择标签条件：显示全部交易的命中结果。", style = MaterialTheme.typography.bodySmall)
        } else if (groupTotals != null) {
            if (groupTotals.isEmpty()) {
                Text("所选标签没有命中任何交易。", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("各分组金额为该组全额，相互独立，不可相加为总额。", style = MaterialTheme.typography.bodySmall)
                condition.tagIds.sortedBy { it.value }.forEach { tag ->
                    val totals = groupTotals[tag]
                    // Fix round (P4-2): the application layer owns the group count — no parallel
                    // UI implementation.
                    val count = tagMerchantGroupTransactionCount(state.loadedEntries, tag)
                    val amounts =
                        totals
                            ?.map { (currency, minor) -> formatMinorUnitsText(currency, minor) }
                            ?.joinToString("，")
                            ?.takeIf { it.isNotEmpty() }
                            ?: "—"
                    Text("「${tagLabelText(tag, tagOptions)}」· $count 笔 · $amounts")
                }
            }
        } else if (state.pageFailure == null) {
            // Fix round (P4-5): a selected tag condition whose first page has not landed yet
            // (no catalog snapshot to fold) shows an explicit loading copy — never a silent
            // gap and never a fabricated "no matches". A landed first-page failure already
            // renders its typed banner above, so the loading copy yields to it.
            val message = "筛选结果载入中…"
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                modifier =
                    Modifier.semantics {
                        contentDescription = message
                        liveRegion = LiveRegionMode.Polite
                    },
            )
        }
        Spacer(Modifier.height(4.dp))
        Text("已加载 ${state.loadedEntries.size} 笔", style = MaterialTheme.typography.bodySmall)
        if (state.hasMore) {
            Button(
                onClick = onLoadMore,
                enabled = !state.pageLoading,
                modifier = Modifier.semantics { contentDescription = "加载更多" },
            ) {
                Text(if (state.pageLoading) "加载中…" else "加载更多")
            }
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        // ---- merchant accumulation (spec section 5.4) ----
        Text("商家普通净支出累计", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text("口径与月度一致：普通净支出，不含特殊消费（储值/预付等）。", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onShiftMonth(-1) }, enabled = !state.accumulationLoading) { Text("前移一月") }
            Text(state.accumulationMonth.toString(), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { onShiftMonth(1) }, enabled = !state.accumulationLoading) { Text("后移一月") }
        }
        when {
            condition.merchantId == null -> Text("未选择商家：先在上方选择一个商家。", style = MaterialTheme.typography.bodySmall)
            state.accumulationLoading -> Text("累计读取中…", style = MaterialTheme.typography.bodySmall)
            else ->
                when (val accumulation = state.accumulation) {
                    null -> Text("累计未加载。", style = MaterialTheme.typography.bodySmall)
                    is MerchantMonthlyAccumulationResult.Success -> {
                        val amounts =
                            accumulation.netExpenseByCurrency.entries
                                .sortedWith(compareBy({ it.key.code }, { it.key.precision }))
                                .joinToString("，") { (currency, minor) -> formatMinorUnitsText(currency, minor) }
                        val message =
                            if (amounts.isEmpty()) {
                                "${state.accumulationMonth} 无该商家的普通净支出。"
                            } else {
                                "${state.accumulationMonth} 普通净支出：$amounts（${accumulation.transactionCount} 笔）"
                            }
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier =
                                Modifier.semantics {
                                    contentDescription = message
                                    liveRegion = LiveRegionMode.Polite
                                },
                        )
                    }
                    is MerchantMonthlyAccumulationResult.Failed -> {
                        val message = tagMerchantFilterFailureText(accumulation.failureCode)
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier =
                                Modifier.semantics {
                                    contentDescription = message
                                    liveRegion = LiveRegionMode.Polite
                                },
                        )
                    }
                }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * The filter's tag-option rule (spec section 5.1): DISABLED items INCLUDED (a disabled tag still
 * matches its historical annotations), tombstoned EXCLUDED (zero references at delete time, so a
 * tombstoned item can never match — it only exists on the audit face). Deliberately different
 * from the editor's selection projection (`active && !tombstoned`). Pure so the UI test pins the
 * production derivation (P503App passes the cached authority through this function).
 */
internal fun tagMerchantFilterTagOptions(items: List<CatalogItem>): List<CatalogItem> = items.filter { !it.tombstoned }

/** The filter's merchant-option rule — same disabled-included/tombstoned-excluded shape. */
internal fun tagMerchantFilterMerchantOptions(items: List<CatalogItem>): List<CatalogItem> = items.filter { !it.tombstoned }

internal fun tagLabelText(
    tagId: TagId,
    tagOptions: List<CatalogItem>?,
): String = tagOptions?.firstOrNull { it.id == tagId.value }?.name ?: tagId.value

/** The typed failure copy (spec section 5.3): a read failure is never rendered as "no matches". */
internal fun tagMerchantFilterFailureText(code: TagMerchantFilterFailureCode): String =
    when (code) {
        TagMerchantFilterFailureCode.FILTER_UNAVAILABLE -> "筛选读取失败，请重试"
        TagMerchantFilterFailureCode.FILTER_MISSING_PROJECTION -> "筛选读取失败：统计时间投影缺失，请刷新后重试"
        TagMerchantFilterFailureCode.FILTER_CATALOG_GENERATION_MISMATCH -> "目录已变化，请返回后重新进入筛选"
        TagMerchantFilterFailureCode.FILTER_BAD_CATALOG -> "目录读取失败，请刷新目录后重试"
    }

/** The pure condition summary (the results headline's content description source). */
internal fun tagMerchantFilterConditionText(
    condition: TagMerchantFilterCondition,
    tagName: (TagId) -> String?,
    merchantName: (MerchantId) -> String?,
): String {
    val parts = mutableListOf<String>()
    if (condition.tagIds.isNotEmpty()) {
        val names = condition.tagIds.sortedBy { it.value }.mapNotNull(tagName)
        val joiner = if (condition.tagMatch == TagMerchantTagMatchMode.All) "且" else "或"
        parts.add("标签 ${names.joinToString(joiner)}")
    }
    condition.merchantId?.let { merchant -> parts.add("商家 ${merchantName(merchant) ?: merchant.value}") }
    return if (parts.isEmpty()) "全部交易" else parts.joinToString("，")
}

/** Per-currency exact minor-units display text (D-120: currencies are never mixed). */
internal fun formatMinorUnitsText(
    currency: CurrencyUnit,
    minorUnits: Long,
): String = "${currency.code} ${formatMinorUnits(minorUnits, currency.precision)}"
