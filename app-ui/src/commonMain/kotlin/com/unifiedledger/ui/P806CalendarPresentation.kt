package com.unifiedledger.ui

import com.unifiedledger.application.P806CalendarCurrencyView
import com.unifiedledger.application.P806CalendarDayView
import com.unifiedledger.application.P806CalendarView
import com.unifiedledger.application.P806SnapshotFailure
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus

/*
 * P8-06 batch 4 (D-362 1; spec sections 3.7/3.8/3.10): the monthly calendar's pure presentation
 * decisions. Everything here is a pure function over the calendar state or a calendar payload, so
 * the JVM tests hold the six faces, the month label, the complete grid (leading blanks, 7 columns,
 * leap years and month ends) and every cell's accessibility text without a Compose runtime.
 */

/**
 * The six distinguishable faces of one calendar month (D-343 section 3.7.7), in the frozen priority
 * order: [UNWIRED] first (no capability claim at all), then [NOT_LOADED] (nothing requested, no
 * failure), then an in-flight [LOADING], then a typed [FAILED], then the two honest successes —
 * [READY_EMPTY] (the accepted month has no contributing transaction at all) and
 * [READY_NON_EMPTY]. A failed/loading face keeps the last accepted payload visible; both READY faces
 * render the full grid, because every date cell must exist even when its count is zero.
 */
internal enum class P806CalendarFace {
    UNWIRED,
    NOT_LOADED,
    LOADING,
    FAILED,
    READY_EMPTY,
    READY_NON_EMPTY,
}

/** The face decision over the calendar's explicit state (see [P806CalendarFace]). */
internal fun p806CalendarFace(state: P503AppState.Calendar): P806CalendarFace =
    when {
        state.unwired -> P806CalendarFace.UNWIRED
        state.loaded == null && !state.loading && state.failure == null -> P806CalendarFace.NOT_LOADED
        state.loading -> P806CalendarFace.LOADING
        state.failure != null -> P806CalendarFace.FAILED
        state.loaded == null -> P806CalendarFace.NOT_LOADED
        state.loaded.days.all { day -> day.transactionCount == 0 } -> P806CalendarFace.READY_EMPTY
        else -> P806CalendarFace.READY_NON_EMPTY
    }

/** The calendar's cursor month label (stable, no platform date formatting). */
internal fun p806CalendarMonthLabel(month: YearMonth): String = "${month.year}年${month.month.ordinal + 1}月"

/**
 * The frozen initial month (P8-06 batch 4): the overview's shared `selectedMonth`, else the host's
 * resolved Shanghai current month. `null` means no usable month — [p806CalendarEntryAvailable] then
 * keeps the entry off and no guessed month is opened.
 */
internal fun p806CalendarInitialMonth(
    selectedMonth: YearMonth?,
    resolvedCurrentMonth: YearMonth?,
): YearMonth? = selectedMonth ?: resolvedCurrentMonth

/**
 * Whether the HOME 收支日历 entry may render AND open (P8-06 batch 4): the graph wires the calendar
 * read AND the frozen initial month resolves. The selected month takes precedence, so a wired graph
 * whose clock could not resolve 本月 still opens on the shared selected month; with no selected month
 * and no resolved current month, the entry stays off rather than opening a guessed month.
 */
internal fun p806CalendarEntryAvailable(
    wired: Boolean,
    selectedMonth: YearMonth?,
    resolvedCurrentMonth: YearMonth?,
): Boolean = wired && p806CalendarInitialMonth(selectedMonth, resolvedCurrentMonth) != null

/** The previous/next month of the calendar's OWN cursor (a shift never touches the overview). */
internal fun p806CalendarShiftedMonth(
    month: YearMonth,
    offset: Int,
): YearMonth = month.plus(offset, kotlinx.datetime.DateTimeUnit.MONTH)
/** One date cell: the date itself plus its accepted payload (null only for a malformed payload). */
internal data class P806CalendarCell(
    val date: LocalDate,
    val day: P806CalendarDayView?,
)

/**
 * The month grid: the leading blank cells before the first day (Monday = column 0), then one cell
 * per date of the month, padded to whole weeks. Every date of the month has a cell; a
 * zero-contribution day is a REAL payload day with explicit zero rows, never a blank.
 */
internal data class P806CalendarGrid(
    val leadingBlanks: Int,
    val weeks: List<List<P806CalendarCell?>>,
)

/** Builds the complete month grid from the accepted payload (see [P806CalendarGrid]). */
internal fun p806CalendarGrid(
    month: YearMonth,
    days: List<P806CalendarDayView>,
): P806CalendarGrid {
    val byDate = days.associateBy { day -> day.date }
    val count = p806CalendarDayCount(month)
    val cells =
        (1..count).map { dayOfMonth ->
            val date = LocalDate(month.year, month.month, dayOfMonth)
            P806CalendarCell(date = date, day = byDate[date])
        }
    val leading = LocalDate(month.year, month.month, 1).dayOfWeek.isoDayNumber - 1
    val leadingCells: List<P806CalendarCell?> = List(leading) { null }
    val all: List<P806CalendarCell?> = leadingCells + cells
    val trailing = (7 - all.size % 7) % 7
    return P806CalendarGrid(
        leadingBlanks = leading,
        weeks = (all + List(trailing) { null }).chunked(7),
    )
}

/**
 * One cell's accessibility text: the date, every axis currency's exact signed expense and income
 * amounts, and the day's ONE global distinct transaction count. No currency is merged or converted
 * and the intensity tint is never the only carrier of any of these facts.
 */
internal fun p806CalendarCellContentDescription(day: P806CalendarDayView): String {
    val amounts =
        if (day.currencies.isEmpty()) {
            "无币种收支"
        } else {
            day.currencies.joinToString("；") { row -> p806CalendarCurrencyText(row) }
        }
    return "${day.date}，$amounts；共 ${day.transactionCount} 笔"
}

/** One currency's exact signed expense/income pair for display and accessibility text. */
internal fun p806CalendarCurrencyText(row: P806CalendarCurrencyView): String =
    "${row.currency.code} 支出 ${formatMinorUnits(row.netExpenseMinorUnits, row.currency.precision)}" +
        "、收入 ${formatMinorUnits(row.ordinaryIncomeMinorUnits, row.currency.precision)}"

/**
 * The deterministic, pure intensity level (0..4) of one day's net expense against the month's
 * largest |net expense| on the same currency axis. It is a VISUAL auxiliary only: 0 stays level 0
 * (income-only and zero days), and a positive magnitude scales linearly into 1..4 with integer
 * arithmetic. The exact colours and levels stay a batch-6 device-evidence item (D-362).
 */
internal fun p806CalendarIntensityLevel(
    netExpenseMinorUnits: Long,
    monthMaxExpenseMinorUnits: Long,
): Int {
    if (netExpenseMinorUnits <= 0L) return 0
    val maximum = monthMaxExpenseMinorUnits.coerceAtLeast(0L)
    if (maximum <= 0L) return 0
    // Pure integer bucketing into four equal quarters of the month maximum (no floating point, no
    // overflow-prone multiplication): the exact levels remain a batch-6 visual-evidence item.
    val step = (maximum / 4L).coerceAtLeast(1L)
    return (((netExpenseMinorUnits - 1L) / step) + 1L).coerceIn(1L, 4L).toInt()
}

/** The month's largest positive net-expense magnitude over every day and axis currency. */
internal fun p806CalendarMonthMaxExpense(loaded: P806CalendarView): Long =
    loaded.days
        .flatMap { day -> day.currencies }
        .maxOfOrNull { row -> row.netExpenseMinorUnits }
        ?.coerceAtLeast(0L)
        ?: 0L

/** The typed failure copy for the calendar — never a zero month and never an empty claim. */
internal fun p806CalendarFailureText(failure: P806SnapshotFailure): String =
    when (failure) {
        P806SnapshotFailure.Unavailable -> "日历读取失败，可点击重试。"
        P806SnapshotFailure.CatalogVersionMismatch -> "账目目录已更新，可点击重试。"
        is P806SnapshotFailure.CatalogReference -> "目录引用不一致（缺账户 / 跨账本 / 币种不符），日历不可用。"
        P806SnapshotFailure.MissingProjection -> "账目缺少统计时间投影，日历不可用。"
        P806SnapshotFailure.OccurredAtUnparseable -> "存在无法解析的资金发生时间，日历不可用。"
        P806SnapshotFailure.InvalidState -> "日历数据不一致，不可用。"
        P806SnapshotFailure.InvalidWindow -> "日历时间范围无效，不可用。"
    }

/**
 * The label of the last accepted payload when the shown payload may NOT be the current cursor's own
 * success: its own month and read instant, so old context is never mistaken for the current month.
 * The label appears while a request is in flight or failed AND — independently of both — whenever
 * the retained payload's own month differs from the cursor month (a month switch keeps the previous
 * success visible until the new landing). `null` only when the shown payload already belongs to the
 * current cursor month and neither a read nor a failure is outstanding.
 */
internal fun p806CalendarOldContextText(state: P503AppState.Calendar): String? {
    val loaded = state.loaded ?: return null
    if (loaded.month == state.month && !state.loading && state.failure == null) return null
    val monthText = p806CalendarMonthLabel(loaded.month)
    val asOfText = state.landedAsOf?.let { instant -> " · 读取时点 $instant" }.orEmpty()
    return "以下为 $monthText 上次成功读取的内容，可能已过期$asOfText。"
}
