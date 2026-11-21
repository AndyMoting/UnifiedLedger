package com.unifiedledger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.unifiedledger.application.P806CalendarDayView
import com.unifiedledger.application.P806CalendarView
import com.unifiedledger.ui.theme.LocalP8Palette

/**
 * P8-06 batch 4 (D-362 1; spec sections 3.7/3.8/3.10): the read-only monthly calendar. The header
 * owns the calendar's OWN month cursor and its ‹ / › steps (never the HOME month cursor); the body
 * renders the accepted month as a complete grid — every date has a cell, including
 * zero-contribution days with explicit per-currency zero rows and a zero global count.
 *
 * The six faces ([P806CalendarFace]) are distinct: an unwired graph, the not-loaded month, an
 * in-flight read, a typed failure, an empty success and a non-empty success. A loading or failed
 * request keeps the last accepted payload visible and LABELS it with its own month (it may be the
 * previous month); only an empty SUCCESS renders the explicit empty copy, and it still shows the
 * full grid. The intensity tint derives from existing palette colours and is auxiliary only: every
 * cell's date, exact signed per-currency amounts and global count is text, and the same facts are
 * in its accessibility description.
 *
 * Zero write entries: the only interactions are 返回, the month steps, retry/read and a day cell
 * opening the shared detail page with that day's OWN window.
 */
@Composable
internal fun P503CalendarScreen(
    state: P503AppState.Calendar,
    onClose: () -> Unit,
    onShiftMonth: (Int) -> Unit,
    onRetry: () -> Unit,
    onOpenDay: ((P806CalendarDayView, ListScrollPosition?) -> Unit)?,
    restoreScrollTo: ListScrollPosition? = null,
    onScrollRestored: () -> Unit = {},
) {
    val palette = LocalP8Palette.current
    val scrollState = rememberScrollState()
    val sampleScroll: () -> ListScrollPosition = { ListScrollPosition(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = scrollState.value) }
    val pendingRestore = restoreScrollTo
    LaunchedEffect(pendingRestore) {
        if (pendingRestore != null) {
            runCatching { scrollState.scrollTo(pendingRestore.firstVisibleItemScrollOffset) }
            onScrollRestored()
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("收支日历", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { onShiftMonth(-1) }) { Text("‹") }
            Text(
                p806CalendarMonthLabel(state.month),
                style = MaterialTheme.typography.titleMedium,
                color = palette.onSurface,
            )
            TextButton(onClick = { onShiftMonth(1) }) { Text("›") }
            OutlinedButton(onClick = onClose) { Text("返回") }
        }
        Spacer(Modifier.height(12.dp))
        when (p806CalendarFace(state)) {
            P806CalendarFace.UNWIRED ->
                Text(P806_CALENDAR_UNWIRED_TEXT, style = MaterialTheme.typography.bodyLarge)
            P806CalendarFace.NOT_LOADED -> {
                Text(P806_CALENDAR_NOT_LOADED_TEXT, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = onRetry) { Text(P806_CALENDAR_LOAD_ACTION_TEXT) }
            }
            P806CalendarFace.LOADING -> {
                Text(P806_CALENDAR_LOADING_TEXT, style = MaterialTheme.typography.bodyMedium)
                p806CalendarOldContextText(state)?.let { oldContext ->
                    Text(oldContext, style = MaterialTheme.typography.bodySmall)
                }
                state.loaded?.let { loaded ->
                    P806CalendarGridBody(loaded, onOpenDay, sampleScroll)
                }
            }
            P806CalendarFace.FAILED -> {
                Text(
                    p806CalendarFailureText(state.failure ?: com.unifiedledger.application.P806SnapshotFailure.Unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                p806CalendarOldContextText(state)?.let { oldContext ->
                    Text(oldContext, style = MaterialTheme.typography.bodySmall)
                }
                state.loaded?.let { loaded ->
                    P806CalendarGridBody(loaded, onOpenDay, sampleScroll)
                }
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = onRetry) { Text("重试") }
            }
            P806CalendarFace.READY_EMPTY -> {
                state.loaded?.let { loaded -> P806CalendarGridBody(loaded, onOpenDay, sampleScroll) }
                Text(P806_CALENDAR_EMPTY_TEXT, style = MaterialTheme.typography.bodyLarge)
            }
            P806CalendarFace.READY_NON_EMPTY ->
                state.loaded?.let { loaded -> P806CalendarGridBody(loaded, onOpenDay, sampleScroll) }
        }
    }
}

/** The explicit not-loaded copy. */
internal const val P806_CALENDAR_NOT_LOADED_TEXT: String = "尚未加载日历。"

/** The first/next month read in flight. */
internal const val P806_CALENDAR_LOADING_TEXT: String = "正在读取日历…"

/** The graph wires no calendar read: its own state, never an empty month. */
internal const val P806_CALENDAR_UNWIRED_TEXT: String = "收支日历不可用（本版本未接线）。"

/** The empty SUCCESS copy: the grid is still rendered above it. */
internal const val P806_CALENDAR_EMPTY_TEXT: String = "该月暂无收支。"

/** The explicit load/retry action label. */
internal const val P806_CALENDAR_LOAD_ACTION_TEXT: String = "读取日历"

/** The weekday header, Monday first (the grid's ISO order). */
private val P806_WEEKDAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

@Composable
private fun P806CalendarGridBody(
    loaded: P806CalendarView,
    onOpenDay: ((P806CalendarDayView, ListScrollPosition?) -> Unit)?,
    sampleScroll: () -> ListScrollPosition,
) {
    val maxExpense = p806CalendarMonthMaxExpense(loaded)
    Row(Modifier.fillMaxWidth()) {
        P806_WEEKDAY_LABELS.forEach { label ->
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).padding(vertical = 4.dp),
            )
        }
    }
    val grid = p806CalendarGrid(loaded.month, loaded.days)
    grid.weeks.forEach { week ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            week.forEach { cell ->
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(2.dp),
                ) {
                    if (cell != null) {
                        P806CalendarDayCell(cell, maxExpense, onOpenDay, sampleScroll)
                    } else {
                        Spacer(Modifier.height(1.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun P806CalendarDayCell(
    cell: P806CalendarCell,
    monthMaxExpenseMinorUnits: Long,
    onOpenDay: ((P806CalendarDayView, ListScrollPosition?) -> Unit)?,
    sampleScroll: () -> ListScrollPosition,
) {
    val palette = LocalP8Palette.current
    val day = cell.day
    val level = day?.let { value -> p806CalendarIntensityLevel(p806CalendarCellExpense(value), monthMaxExpenseMinorUnits) } ?: 0
    // Existing palette colour at a level-derived alpha: an auxiliary tint, never a new colour token
    // and never the only carrier (the text below repeats every fact).
    val tint = palette.card.copy(alpha = 0.35f + level * 0.15f)
    val description = day?.let(::p806CalendarCellContentDescription) ?: "${cell.date}，无数据"
    val openDay = onOpenDay
    val clickModifier =
        if (day != null && openDay != null) {
            Modifier.clickable { openDay(day, sampleScroll()) }
        } else {
            Modifier
        }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(tint)
                .then(clickModifier)
                .semantics { contentDescription = description }
                .padding(4.dp),
    ) {
        Text(cell.date.dayOfMonth.toString(), style = MaterialTheme.typography.bodySmall, color = palette.onSurface)
        if (day != null) {
            day.currencies.forEach { row ->
                Text(
                    p806CalendarCurrencyText(row),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.onSurfaceVariant,
                )
            }
            Text("${day.transactionCount} 笔", style = MaterialTheme.typography.labelSmall, color = palette.onSurfaceVariant)
        }
    }
}

/** The cell's primary intensity magnitude: its largest positive net expense across the axis. */
private fun p806CalendarCellExpense(day: P806CalendarDayView): Long =
    day.currencies.maxOfOrNull { row -> row.netExpenseMinorUnits }?.coerceAtLeast(0L) ?: 0L
