package com.unifiedledger.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.unifiedledger.application.TagMerchantTagMatchMode
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CatalogItem
import com.unifiedledger.domain.MerchantId
import com.unifiedledger.domain.TagId
import com.unifiedledger.ui.theme.LocalP8Palette
import kotlinx.datetime.LocalDate

/**
 * P8-02 i2a (D7-1..D7-7; spec sections 2.1/2.4/2.5): the independent search result surface. A thin
 * renderer like the reconciliation/P7-08 screens — every affordance takes a callback the host wires
 * and the screen never reads the facade; every load-bearing decision (which face, whether submit is
 * allowed, how the draft becomes the condition group, how a failure is worded) is a pure function in
 * `P503SearchPresentation.kt`.
 *
 * The condition area carries the four ENABLED items the slice implements — keyword, the mandatory
 * time window (本月 / 近 90 天 / 自定义起止), the tag multi-select with its OR/AND mode, and the
 * optional merchant — plus the two canvas chips (账户, 金额区间) that stay VISIBLE and permanently
 * disabled (D7-6 / V-12: never silently hidden, never silently active).
 *
 * The result body is a day-grouped list over the existing building blocks — `p8GroupByDay`,
 * `P8DayGroupHeader`, `LedgerEntryFlowRow` and `P8FlowFooter` — inside ONE [LazyColumn] whose state is
 * hoisted (D7-5: the home shape, not the queue's `Column + ScrollState`), so the sampled position,
 * the host sampler and the restore all address the same object.
 *
 * 诚实呈现 (V-7 / D-089): a typed read failure renders its own copy; the guide face is the
 * never-requested state and the empty face is the ONLY one allowed to claim an empty result, so a
 * failure can never read as "没有找到". The surface has zero write entries and no calendar entry
 * (V-11 — the calendar belongs to P8-06).
 *
 * The rows are read-only in this slice: routing the read-only transaction detail from here needs a
 * back target the search surface does not carry yet (registered residual), so the row is presented
 * without a click rather than with one the reducer would absorb.
 */
@Composable
internal fun P503SearchScreen(
    state: P503AppState.Search,
    accountNames: Map<AccountId, String>,
    tagOptions: List<CatalogItem>?,
    merchantOptions: List<CatalogItem>?,
    onKeywordChange: (String) -> Unit,
    onSelectWindow: (SearchWindowChoice) -> Unit,
    onUpdateCustomStart: (String) -> Unit,
    onUpdateCustomEnd: (String) -> Unit,
    onToggleTag: (TagId) -> Unit,
    onSetTagMatch: (TagMerchantTagMatchMode) -> Unit,
    onSelectMerchant: (MerchantId?) -> Unit,
    onSubmit: () -> Unit,
    onLoadMore: () -> Unit,
    onRetryLoadMore: () -> Unit,
    onClose: () -> Unit,
    registerListState: ((LazyListState) -> Unit)? = null,
    restoreScrollTo: ListScrollPosition? = null,
    onScrollRestored: () -> Unit = {},
) {
    val palette = LocalP8Palette.current
    // 返回保滚动位: the list state is HOISTED to the screen so the sampled position and the restored
    // one address the same object (the home list's mechanism, reused as-is for D7-5's LazyColumn).
    // The restore slot is registered for a future secondary surface and is never filled today.
    val listState = rememberLazyListState()
    LaunchedEffect(registerListState) { registerListState?.invoke(listState) }
    val pendingRestore = restoreScrollTo
    LaunchedEffect(pendingRestore) {
        if (pendingRestore != null) {
            runCatching {
                listState.scrollToItem(pendingRestore.firstVisibleItemIndex, pendingRestore.firstVisibleItemScrollOffset)
            }
            onScrollRestored()
        }
    }
    // Grouping preserves the port's frozen order; a day's header states its count only (never a sum).
    val groups = remember(state.entries) { p8GroupByDay(state.entries) }
    // Collapse is a per-day view preference (the home list's shape), never ledger state.
    val collapsedDays = remember { mutableStateMapOf<LocalDate, Boolean>() }
    val window = searchWindowOf(state.window, state.customStartText, state.customEndText)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(P8_SEARCH_TITLE, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onClose) { Text("返回") }
            }
            Spacer(Modifier.height(8.dp))
        }
        // ---- condition: keyword ----
        item {
            OutlinedTextField(
                value = state.keywordDraft,
                onValueChange = onKeywordChange,
                label = { Text(P8_SEARCH_KEYWORD_LABEL) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
        }
        // ---- condition: the mandatory time window (three choices) ----
        item {
            Text(P8_SEARCH_WINDOW_HEADER, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            // Horizontally scrollable so the three chips stay reachable at the 1.3x font scale
            // instead of being clipped (the device leg of that check belongs to i2b).
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SearchWindowChoice.entries.forEach { choice ->
                    SearchWindowChip(
                        label = searchWindowLabel(choice),
                        selected = state.window == choice,
                        onClick = { onSelectWindow(choice) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (state.window == SearchWindowChoice.CUSTOM) {
            item {
                OutlinedTextField(
                    value = state.customStartText,
                    onValueChange = onUpdateCustomStart,
                    label = { Text(P8_SEARCH_CUSTOM_START_LABEL) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = state.customEndText,
                    onValueChange = onUpdateCustomEnd,
                    label = { Text(P8_SEARCH_CUSTOM_END_LABEL) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // An unresolvable range disables submit instead of producing a typed window failure;
                // the warning waits until at least one custom field is non-blank (no pre-typing alarm).
                if (window == null && (state.customStartText.isNotBlank() || state.customEndText.isNotBlank())) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        P8_SEARCH_CUSTOM_INVALID_TEXT,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        item {
            // 关键词可空 (D7-6): an empty keyword degrades to the window + tag/merchant filter.
            // D7-8: a semantics name only this button carries. Its visible text repeats the screen
            // title (搜索), and a by-text tap resolves to the FIRST node in document order — the title.
            Button(
                onClick = onSubmit,
                modifier = Modifier.semantics { contentDescription = "提交搜索" },
                enabled = searchSubmitEnabled(state),
            ) { Text(P8_SEARCH_SUBMIT_LABEL) }
            Spacer(Modifier.height(12.dp))
        }
        // ---- condition: tags (multi-select, disabled included / tombstoned excluded) ----
        item {
            Text(P8_SEARCH_TAG_HEADER, style = MaterialTheme.typography.titleSmall)
            when {
                tagOptions == null -> Text(P8_SEARCH_TAGS_LOADING_TEXT, style = MaterialTheme.typography.bodySmall)
                tagOptions.isEmpty() -> Text(P8_SEARCH_TAGS_EMPTY_TEXT, style = MaterialTheme.typography.bodySmall)
                else ->
                    tagOptions.forEach { tag ->
                        val id = TagId(tag.id)
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = id in state.tagIds, onCheckedChange = { onToggleTag(id) })
                            Text(tag.name + if (tag.active) "" else "（已停用）")
                        }
                    }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = state.tagMatch == TagMerchantTagMatchMode.Any,
                    onClick = { onSetTagMatch(TagMerchantTagMatchMode.Any) },
                    enabled = state.tagIds.isNotEmpty(),
                )
                Text(searchTagMatchLabel(TagMerchantTagMatchMode.Any))
                Spacer(Modifier.width(8.dp))
                RadioButton(
                    selected = state.tagMatch == TagMerchantTagMatchMode.All,
                    onClick = { onSetTagMatch(TagMerchantTagMatchMode.All) },
                    enabled = state.tagIds.isNotEmpty(),
                )
                Text(searchTagMatchLabel(TagMerchantTagMatchMode.All))
            }
            Spacer(Modifier.height(8.dp))
        }
        // ---- condition: merchant (optional, single select) ----
        item {
            Text(P8_SEARCH_MERCHANT_HEADER, style = MaterialTheme.typography.titleSmall)
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = state.merchantId == null, onClick = { onSelectMerchant(null) })
                Text(P8_SEARCH_NO_MERCHANT_LABEL)
            }
            if (merchantOptions == null) {
                Text(P8_SEARCH_MERCHANTS_LOADING_TEXT, style = MaterialTheme.typography.bodySmall)
            } else {
                merchantOptions.forEach { merchant ->
                    val id = MerchantId(merchant.id)
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = state.merchantId == id, onClick = { onSelectMerchant(id) })
                        Text(merchant.name + if (merchant.active) "" else "（已停用）")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        // ---- the two canvas dimensions this slice does not implement (V-12) ----
        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                P8_SEARCH_DISABLED_CHIPS.forEach { label ->
                    // 可见但不可点: the chip is drawn and permanently disabled — the click can never
                    // reach the reducer, and the dimension is never silently hidden (spec 2.4).
                    OutlinedButton(
                        onClick = {},
                        enabled = P8_SEARCH_DISABLED_CHIP_ENABLED,
                        shape = RoundedCornerShape(percent = 50),
                    ) {
                        Text(label)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        // ---- typed failure banner (kept ABOVE the rows: a failed more-page keeps them, V-7) ----
        state.pageFailure?.let { failure ->
            item(key = "search-failure") {
                val message = searchFailureText(failure)
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
                Spacer(Modifier.height(8.dp))
            }
        }
        when (searchFace(state)) {
            SearchFace.GUIDE ->
                item {
                    Text(
                        P8_SEARCH_GUIDE_TEXT,
                        style = MaterialTheme.typography.bodyLarge,
                        color = palette.onSurfaceVariant,
                    )
                }
            SearchFace.LOADING ->
                item {
                    Text(P8_SEARCH_LOADING_TEXT, style = MaterialTheme.typography.bodyMedium)
                }
            SearchFace.UNAVAILABLE ->
                item {
                    // The typed copy already renders in the banner above; this face owns the retry.
                    OutlinedButton(onClick = onRetryLoadMore) { Text("重试") }
                }
            SearchFace.EMPTY ->
                item {
                    // A successful read with zero rows — the only face that may claim an empty result.
                    Text(P8_SEARCH_EMPTY_TEXT, style = MaterialTheme.typography.bodyLarge)
                }
            SearchFace.LIST -> {
                item {
                    Text(
                        "$P8_SEARCH_RESULTS_HEADER · 共 ${state.totalCount} 笔",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                groups.forEach { group ->
                    item(key = "day-${group.date}") {
                        P8DayGroupHeader(
                            group = group,
                            expanded = collapsedDays[group.date] != true,
                            onToggle = { collapsedDays[group.date] = collapsedDays[group.date] != true },
                        )
                    }
                    if (collapsedDays[group.date] != true) {
                        items(group.rows, key = { it.transactionId.value }) { row ->
                            // D7-1: the SAME flow row, no merchant/tag badge projection. Read-only
                            // here (no detail route from this surface yet — registered residual).
                            LedgerEntryFlowRow(row, accountNames, onSelectTransaction = {}, interactionsEnabled = false)
                            HorizontalDivider()
                        }
                    }
                }
                item(key = "search-footer") {
                    P8FlowFooter(
                        state = searchFooterState(state),
                        onLoadMore = onLoadMore,
                        onRetryLoadMore = onRetryLoadMore,
                    )
                }
            }
        }
    }
}

/**
 * One window chip: filled when selected, outlined otherwise (the canvas's pill shape). Rendered as
 * the shared button family rather than a `FilterChip` so the surface uses only the component
 * vocabulary the rest of app-ui already renders.
 */
@Composable
private fun SearchWindowChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick, shape = RoundedCornerShape(percent = 50)) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, shape = RoundedCornerShape(percent = 50)) { Text(label) }
    }
}
