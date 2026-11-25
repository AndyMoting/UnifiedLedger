package com.unifiedledger.android

import android.app.Instrumentation
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.unifiedledger.application.LedgerEntryRow
import com.unifiedledger.application.ManualRefundCandidate
import com.unifiedledger.application.ManualRefundCandidatesResult
import com.unifiedledger.application.P806CalendarCurrencyView
import com.unifiedledger.application.P806CalendarDayView
import com.unifiedledger.application.P806CalendarView
import com.unifiedledger.application.P806CategoryCurrencyTotal
import com.unifiedledger.application.P806CategoryView
import com.unifiedledger.application.P806CurrencyView
import com.unifiedledger.application.P806DrilldownView
import com.unifiedledger.application.P806Period
import com.unifiedledger.application.P806PeriodKind
import com.unifiedledger.application.P806ReportView
import com.unifiedledger.application.P806SnapshotResult
import com.unifiedledger.application.P806Window
import com.unifiedledger.application.TransactionDetail
import com.unifiedledger.application.TransactionDetailLeg
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.time.Instant

/**
 * P8-07 Q2 flow 3 device evidence: MANUAL REFUND -> CROSS-MONTH REPORT -> DAY DETAIL, on the dev
 * device, over the shared anonymous starting seed that [P807Q2Flow1Seed] builds.
 *
 * WHAT THIS FILE IS. The flow's own pieces, and only those: the copy table this flow asserts
 * against, the test-side report/calendar/drilldown/refund-candidate read accessor, and the form,
 * report, calendar and day-detail UI steps that the frozen [P807Q2Flow1Ui] does not own. The seed,
 * the formal-ledger snapshot, the version face and the generic launch/seek/click discipline are
 * reused exactly as they are; nothing here re-implements them.
 *
 * WHICH AUTHORITY EACH COPY COMES FROM. Every literal in [P807Q2Flow3Facts] is `internal` to
 * `app-ui`, which androidTest cannot see, so each one is mirrored verbatim next to the `app-ui` line
 * that produces it (the P805/P807 mirror discipline). A product copy change therefore fails this
 * flow's assertion loudly instead of passing against a stale memory of it.
 *
 * WHY THE STRUCTURAL PRIMITIVES ARE RE-STATED HERE. The type row, the original-transaction row and
 * the arrival-account row are all `Row { RadioButton; Text }` with no text of their own on the radio
 * (`P503EditScreen.kt:862-881` for the type choice, `:490-500` for a candidate, `:514-521` for an
 * arrival account), so each one is named only by its row's own visible label: the structure-first
 * anchor with the derived coordinate fallback that the frozen flow-2 support file introduced
 * (`P807Q2Flow2DeviceSupport.kt:829-846`, `:877-932`). That file is frozen for this batch, so the
 * shape is restated rather than widened; the technique is identical and every acceptance is the
 * click's own observable effect, never the click itself.
 *
 * THE READ ACCESSOR IS TEST-SIDE, NOT A PRODUCT SEAM. [P807Q2Flow3Ledger] reaches the LAUNCHED
 * app's one runtime owner through `ledgerProcessHolderFor(context).ledger` (the shape
 * [P807Q2Flow1Ledger] already uses) and calls the composition-root-bound
 * `P806ReportQuery`/`P806CalendarQuery`/`P806DrilldownQuery` and `readManualRefundCandidates` from
 * inside that operation lease (`P503LedgerFacade.kt:319,415,419,423`). Every assertion it feeds is
 * therefore an authoritative product read, taken under the same lease a product screen would hold;
 * it adds no read path to the product.
 *
 * WHAT THIS FILE DOES NOT DO. It writes nothing to the ledger, it never reads SQL, and it never
 * judges a product rule itself: the UI copy and the port payloads are compared, and the
 * formal-ledger snapshot equality is the zero-accounting-effect oracle.
 */
internal object P807Q2Flow3Facts {
    /** The one grep-able device-evidence prefix of this flow. */
    const val PREFIX = "P807-Q2FLOW3"

    // ---------------------------------------------------------------- entry surface (app-ui mirrors)

    /** The dock's record-plus carries only this description — `P503TabShell.kt:131`. */
    const val NEW_ENTRY_DESCRIPTION = "新增支出"

    /** The expense draft's title — `P503EditScreen.kt:232`. */
    const val ENTRY_TITLE_EXPENSE = "新增手工支出"

    /** The refund draft's title — `P503EditScreen.kt:237`. */
    const val ENTRY_TITLE_REFUND = "新增退款到账"

    /** The refund type radio's label — `P503RefundEntryPresentation.kt:32`. */
    const val TYPE_REFUND = "退款到账"

    /** The three selector labels of the refund field chain — `P503EditScreen.kt:475,509,920`. */
    const val LABEL_ORIGINAL = "原交易"
    const val LABEL_ARRIVAL = "到账账户"
    const val LABEL_OCCURRED_AT = "发生时间"

    /**
     * The amount label in its two states: it names the SELECTED arrival account's currency, so it
     * reads the em dash placeholder until an arrival account is chosen — `P503EditScreen.kt:525-533`.
     */
    const val LABEL_AMOUNT_UNSELECTED = "退款金额（—）"
    const val LABEL_AMOUNT_CNY = "退款金额（CNY）"

    /** The refund edit surface's close action — `P503EditScreen.kt:248-252`. */
    const val CLOSE = "关闭"

    /** The continue action — `P503EditScreen.kt:702-718`. */
    const val CONTINUE = "继续"

    /** The over-cap amount the flow types first, and the legal full-cap amount it settles on. */
    const val OVER_CAP_AMOUNT_TEXT = "99.99"
    const val LEGAL_AMOUNT_TEXT = "21.00"

    /** The fixed arrival instant typed into the required field (C5: the clock never decides). */
    const val ARRIVAL_INSTANT_TEXT = "2026-11-20T02:00:00Z"

    // ---------------------------------------------------------------- refund copy (app-ui mirrors)

    /**
     * The one refundable candidate's row: `date · category · expense-leg amount · refunded ·
     * remaining` (`refundCandidateRowText`, `P503RefundEntryPresentation.kt:92-102`). The date is the
     * Shanghai local day of the seed's `2026-10-15T02:00:00Z` expense and the category is the
     * catalog's `早餐` leaf (`CatalogManagementUseCases.kt:33`).
     */
    const val CANDIDATE_ROW = "2026-10-15 · 早餐 · 支出腿金额 21.00 · 已退 0.00 · 剩余 21.00"

    /** The empty candidate copy (`REFUND_CANDIDATES_EMPTY_TEXT`, `P503RefundEntryPresentation.kt:67`). */
    const val CANDIDATES_EMPTY = "暂无可退交易"

    /** The structural-cap rejection sentence — `P503RefundEntryPresentation.kt:115`. */
    const val CAP_REJECTION = "退款金额超过原交易的支出金额"

    /** The confirmation page's title — `P503ConfirmationPresentation.kt:51`. */
    const val CONFIRM_TITLE = "确认退款到账"

    /** The confirmation page's two actions — `P503ConfirmationScreen.kt:57-62`. */
    const val CONFIRM_SUBMIT = "确认提交"

    /** The created-result notice — `P503ResultScreen.kt:30`. */
    const val RESULT_CREATED = "支出已创建"

    /**
     * The confirmation page's original-transaction row: the reducer's `date · category` label
     * (`P503Reducer.kt:4964-4971`) over the same candidate.
     */
    const val CONFIRM_ORIGINAL_ROW = "原交易：2026-10-15 · 早餐"

    /**
     * The inherited-category row: the reducer resolves the category from the candidate page
     * (`P503Reducer.kt:4955`), and the page prints it read-only (`P503ConfirmationPresentation.kt:124`).
     */
    const val CONFIRM_CATEGORY_ROW = "继承分类：早餐"

    /** The refund-amount row (`amountValue`, `P503ConfirmationPresentation.kt:125,134-137`). */
    const val CONFIRM_AMOUNT_ROW = "退款金额：21.00 CNY"

    // ---------------------------------------------------------------- report region (app-ui mirrors)

    /** The report region's header and its independent-period statement — `P503ReportRegion.kt:744,214`. */
    const val REPORT_HEADER = "报表期间"
    const val REPORT_PERIOD_NOTICE = "报表期间独立于上方月卡与趋势的月份游标。"

    /** The five period chips' labels; only 自定义 is driven — `P503ReportRegion.kt:204-211`. */
    const val KIND_CUSTOM = "自定义"

    /** The custom window's two field labels and its explicit commit — `P503ReportRegion.kt:787,793,796`. */
    const val CUSTOM_START_LABEL = "起始日期（YYYY-MM-DD）"
    const val CUSTOM_END_LABEL = "结束日期（YYYY-MM-DD）"
    const val CUSTOM_SUBMIT = "查询自定义区间"

    /** The 31-day October and 30-day November windows the flow commits (C5: both fixed). */
    val OCTOBER_START: LocalDate = LocalDate(2026, 10, 1)
    val OCTOBER_END: LocalDate = LocalDate(2026, 10, 31)
    val NOVEMBER_START: LocalDate = LocalDate(2026, 11, 1)
    val NOVEMBER_END: LocalDate = LocalDate(2026, 11, 30)

    /** The arrival instant this flow pins; its Shanghai wall clock is what the form and detail print. */
    val ARRIVAL_INSTANT: Instant = Instant.parse(ARRIVAL_INSTANT_TEXT)

    // ---------------------------------------------------------------- calendar + day detail

    /** The HOME month row's step glyphs and its calendar entry — `P8HomeFlow.kt:268,275,277`. */
    const val MONTH_PREVIOUS = "‹"
    const val MONTH_NEXT = "›"
    const val CALENDAR_ENTRY = "收支日历"

    /** The calendar page's own title — `P503CalendarScreen.kt:72`. */
    const val CALENDAR_TITLE = "收支日历"

    /**
     * The HOME month row's label after one previous step: the domain's FIRST month, which is the
     * seed's earliest statistics month, printed as `YearMonth.toString()`
     * (`P503OverviewScreen.kt:174`; the step's edge is `monthStepEdges.previous`,
     * `P503LedgerViewPresentation.kt:204-214`).
     */
    const val HOME_MONTH_FIRST = "2026-10"

    /** The calendar's own month title for the same two months — `P806CalendarPresentation.kt:49`. */
    const val CALENDAR_MONTH_FIRST = "2026年10月"
    const val CALENDAR_MONTH_ARRIVAL = "2026年11月"

    /**
     * The arrival day's cell text: the date, every axis currency's signed pair and the day's one
     * global count (`p806CalendarCellContentDescription`, `P806CalendarPresentation.kt:122-130`).
     */
    const val ARRIVAL_DAY_CELL = "2026-11-20，CNY 支出 -21.00、收入 0.00；共 1 笔"

    /** The arrival day itself (the refund's statistics date in the reporting zone). */
    val ARRIVAL_DAY: LocalDate = LocalDate(2026, 11, 20)

    /** The day-detail surface's title — `P503ReportDrilldownScreen.kt:71`. */
    const val DRILLDOWN_TITLE = "报表明细"

    /** The day-detail count line for the one refund — `P503ReportDrilldownScreen.kt:143`. */
    const val DRILLDOWN_COUNT_ONE = "共 1 笔明细"

    /**
     * The day-detail header's date part. The product appends ` · 读取时点 <asOf>` whenever the read
     * captured an instant (`P806DrilldownPresentation.kt:97-105`), so this flow asserts the date
     * prefix rather than the whole line: the suffix is the run's own wall clock, not a fixture.
     */
    const val DAY_DETAIL_PREFIX = "2026-11-20 明细"

    /** The shared back label — `P503ReportDrilldownScreen.kt:72`; `P503LedgerView.kt:388`. */
    const val BACK = "返回"

    /** The read-only transaction detail's title — `P503LedgerView.kt:386`. */
    const val DETAIL_TITLE = "交易详情"

    // ---------------------------------------------------------------- navigation

    /** The dock tabs — `P503TabShell.kt:111-113`. */
    const val TAB_HOME = "首页"
    const val TAB_ANALYSIS = "分析"

    /** The arrival account option's row text — `P503EditScreen.kt:519`. */
    fun arrivalOption(accountName: String): String = accountName + "（CNY）"

    /** The confirmation page's arrival-account row — `P503ConfirmationPresentation.kt:123`. */
    fun confirmArrivalRow(accountName: String): String = "到账账户：" + accountName

    /** The confirmation page's occurred-at row — `P503ConfirmationScreen.kt:54`. */
    fun confirmTimeRow(instant: Instant): String = "发生时间：" + p807Q2Flow3OccurredAtDisplayText(instant)

    /** The October custom period this flow commits and reads. */
    fun octoberPeriod(): P806Period = P806Period(kind = P806PeriodKind.CUSTOM, customStart = OCTOBER_START, customEnd = OCTOBER_END)

    /** The November custom period this flow commits and reads. */
    fun novemberPeriod(): P806Period = P806Period(kind = P806PeriodKind.CUSTOM, customStart = NOVEMBER_START, customEnd = NOVEMBER_END)
}

/** The reporting zone every product date on these surfaces is read in (`MonthlyBuckets.REPORT_TIME_ZONE`). */
private val REPORT_TIME_ZONE: TimeZone = TimeZone.of("Asia/Shanghai")

/** The page size the edit surface itself reads the refund candidates with (`P503App.kt:6184`). */
private const val REFUND_CANDIDATE_PAGE_SIZE = 50

/**
 * The confirmation page's frozen occurred-at text, mirroring `occurredAtDisplayText`
 * (`OccurredAtPicker.kt:54-58`): the Shanghai wall clock, the `（UTC+8）` annotation and the UTC ISO
 * string, and only the wall clock for dates whose offset the annotation cannot describe.
 */
internal fun p807Q2Flow3OccurredAtDisplayText(instant: Instant): String {
    val local = instant.toLocalDateTime(REPORT_TIME_ZONE)
    val hour = local.hour.toString().padStart(2, '0')
    val minute = local.minute.toString().padStart(2, '0')
    val wallClock = local.date.toString() + " " + hour + ":" + minute
    return if (local.year > 1991) wallClock + "（UTC+8）＝ " + instant else wallClock
}

/** The Shanghai local date of [instant], mirroring `occurredAtLocalDateText` (`OccurredAtPicker.kt:65`). */
internal fun p807Q2Flow3LocalDateText(instant: Instant): String = instant.toLocalDateTime(REPORT_TIME_ZONE).date.toString()

/**
 * Exact minor-unit text at [precision], mirroring `p806MinorUnitsText`
 * (`P503ReportRegion.kt:189-201`) for every value this flow can meet: the refund's own legs are
 * `+/-2100`, so the `Long.MIN_VALUE` branch that function exists for is unreachable here.
 */
internal fun p807Q2Flow3MinorText(
    minorUnits: Long,
    precision: Int,
): String {
    val sign = if (minorUnits < 0L) "-" else ""
    val magnitude = if (minorUnits < 0L) -minorUnits else minorUnits
    val digits = magnitude.toString()
    if (precision == 0) return sign + digits
    val padded = digits.padStart(precision + 1, '0')
    return sign + padded.substring(0, padded.length - precision) + "." + padded.substring(padded.length - precision)
}

/** One resolved period as the region labels it, mirroring `p806ReportResolvedPeriodText` (`P503ReportRegion.kt:217-220`). */
internal fun p807Q2Flow3PeriodLine(
    view: P806ReportView,
    kind: P806PeriodKind,
): String {
    val period = checkNotNull(view.period) { "a successful report read of the committed custom window must carry a resolved period" }
    return p807Q2Flow3PeriodKindLabel(kind) + "：" + period.startDate + " 至 " + period.endDateInclusive + "（" + period.dayCount + " 天）"
}

/** The period kind's display name, mirroring `p806ReportPeriodKindLabel` (`P503ReportRegion.kt:204-211`). */
internal fun p807Q2Flow3PeriodKindLabel(kind: P806PeriodKind): String =
    when (kind) {
        P806PeriodKind.WEEK -> "周"
        P806PeriodKind.MONTH -> "月"
        P806PeriodKind.YEAR -> "年"
        P806PeriodKind.ALL -> "全部"
        P806PeriodKind.CUSTOM -> "自定义"
    }

/**
 * One report read's comparable face: the resolved period, the counts, the detail rows, every axis
 * currency's exact amounts and the category roll-up as strings, so a before/after comparison is a
 * whole period face rather than a spot check.
 *
 * THE LEDGER SPAN IS DELIBERATELY ABSENT. [P806ReportView.span] is the ledger-WIDE effective span
 * read beside the payload (`D-343` section 3.1.3); a refund in November legitimately moves it, while
 * the October period's own values must not move. Including it would make the unchanged-month claim
 * compare a value that is supposed to change.
 */
internal fun p807Q2Flow3ReportLines(view: P806ReportView): List<String> {
    val lines = mutableListOf<String>()
    val period = view.period
    val periodFields =
        if (period == null) {
            "none"
        } else {
            period.startDate.toString() + "|" + period.endDateInclusive + "|" + period.dayCount
        }
    lines += "period|" + periodFields
    lines += "counts|" + view.transactionCount + "|" + view.detailCount + "|" + view.futureTransactionCount
    val detailRowKeys =
        view.detailPage.rows
            .map { row -> p807Q2Flow3RowKey(row) }
            .sorted()
    lines += "detailRows|" + detailRowKeys.joinToString(";")
    view.currencies.forEach { row ->
        lines +=
            "currency|" + row.currency.code + "|" + row.ordinaryIncomeMinorUnits + "|" + row.netExpenseMinorUnits +
            "|" + row.balanceMinorUnits + "|" + row.positiveExpenseMinorUnits + "|" + row.refundMinorUnits +
            "|" + row.dailyAverageMinorUnits + "|" + row.transactionCount
    }
    lines += "expenseCategories|" + view.expenseCategories.joinToString(";") { category -> p807Q2Flow3CategoryFace(category) }
    lines += "incomeCategories|" + view.incomeCategories.joinToString(";") { category -> p807Q2Flow3CategoryFace(category) }
    lines += "uncategorizedExpense|" + p807Q2Flow3TotalFace(view.uncategorizedExpenseTotals)
    lines += "uncategorizedIncome|" + p807Q2Flow3TotalFace(view.uncategorizedIncomeTotals)
    return lines
}

/** One detail row's identity and version, so a face comparison covers "the same rows, same versions". */
private fun p807Q2Flow3RowKey(row: LedgerEntryRow): String = row.transactionId.value + "|" + row.kind + "|" + row.statisticsAt + "|" + row.currentVersionId.value

/** One category row and its children as strings (name plus the exact split of each currency). */
private fun p807Q2Flow3CategoryFace(category: P806CategoryView): String =
    category.categoryName + "|" + p807Q2Flow3TotalFace(category.totals) + "|" +
        category.children.joinToString("+") { child -> child.categoryName + "|" + p807Q2Flow3TotalFace(child.totals) }

/** The exact positive/refund split of one category's currencies, as sorted strings. */
private fun p807Q2Flow3TotalFace(totals: List<P806CategoryCurrencyTotal>): String =
    totals
        .map { total -> total.currency.code + "|" + total.positiveMinorUnits + "|" + total.refundMinorUnits }
        .sorted()
        .joinToString(",")

/**
 * The report lines the region must RENDER for [view], built from the authoritative payload with the
 * region's own templates (`P503ReportRegion.kt:282-309`), in the region's own DOCUMENT order: the
 * resolved period, then the ordinary transaction count, then every axis currency's net expense /
 * ordinary income / balance / daily average (`P503ReportRegion.kt:800,837,841`). The order matters
 * because the flow reaches each line with a forward scroll only.
 *
 * The category section is deliberately absent: its level-2 rows render only after a user expansion
 * (`p806ReportCategoryInitialExpansion`, `P503ReportRegion.kt:343-354`), which this flow does not
 * drive, so asserting them on this surface would assert a collapsed row.
 */
internal fun p807Q2Flow3ReportUiLines(
    view: P806ReportView,
    kind: P806PeriodKind,
): List<String> {
    val lines = mutableListOf(p807Q2Flow3PeriodLine(view, kind))
    lines += "普通收支笔数：" + view.transactionCount
    view.currencies.forEach { row -> lines += p807Q2Flow3CurrencyUiLines(row) }
    return lines
}

/** One currency block's rendered lines, mirroring `P503ReportRegion.kt:282-303`. */
private fun p807Q2Flow3CurrencyUiLines(row: P806CurrencyView): List<String> {
    val precision = row.currency.precision
    val code = row.currency.code
    val average = row.dailyAverageMinorUnits
    val dailyAverage =
        if (average == null) {
            "日均支出 不适用"
        } else {
            "日均支出 " + p807Q2Flow3MinorText(average, precision) + " " + code
        }
    return listOf(
        "净支出（含退款） " + p807Q2Flow3MinorText(row.netExpenseMinorUnits, precision) + " " + code,
        "普通收入 " + p807Q2Flow3MinorText(row.ordinaryIncomeMinorUnits, precision) + " " + code,
        "结余 " + p807Q2Flow3MinorText(row.balanceMinorUnits, precision) + " " + code,
        dailyAverage,
    )
}

/**
 * One calendar read's comparable face: the month, the axis and every day's date, per-currency
 * amounts and global count as strings.
 */
internal fun p807Q2Flow3CalendarLines(view: P806CalendarView): List<String> {
    val lines = mutableListOf<String>()
    lines += "month|" + view.month + "|axis=" + view.axis.joinToString(",") { currency -> currency.code }
    view.days.forEach { day ->
        val amounts = day.currencies.joinToString(";") { row -> p807Q2Flow3CalendarCurrencyText(row) }
        lines += "day|" + day.date + "|" + amounts + "|" + day.transactionCount
    }
    return lines
}

/**
 * One day cell's accessibility text, mirroring `p806CalendarCellContentDescription`
 * (`P806CalendarPresentation.kt:122-130`): the date, every axis currency's exact signed pair and the
 * day's ONE global distinct transaction count.
 */
internal fun p807Q2Flow3CellDescription(day: P806CalendarDayView): String {
    val amounts =
        if (day.currencies.isEmpty()) {
            "无币种收支"
        } else {
            day.currencies.joinToString("；") { row -> p807Q2Flow3CalendarCurrencyText(row) }
        }
    return day.date.toString() + "，" + amounts + "；共 " + day.transactionCount + " 笔"
}

/** One calendar currency's signed pair, mirroring `p806CalendarCurrencyText` (`P806CalendarPresentation.kt:139-141`). */
internal fun p807Q2Flow3CalendarCurrencyText(row: P806CalendarCurrencyView): String =
    row.currency.code + " 支出 " + p807Q2Flow3MinorText(row.netExpenseMinorUnits, row.currency.precision) +
        "、收入 " + p807Q2Flow3MinorText(row.ordinaryIncomeMinorUnits, row.currency.precision)

/** One detail-list read's comparable face: the window, the exact count and every row's identity. */
internal fun p807Q2Flow3DrilldownLines(view: P806DrilldownView): List<String> {
    val lines = mutableListOf<String>()
    lines += "window|" + view.window.startEpochNanos + "|" + view.window.endEpochNanos
    lines += "totalCount|" + view.totalCount
    val rowKeys =
        view.page.rows
            .map { row -> p807Q2Flow3RowKey(row) }
            .sorted()
    lines += "rows|" + rowKeys.joinToString(";")
    return lines
}

/**
 * One flow row's visible headline, mirroring `P503OverviewScreen.kt:307-308`: the effective kind, the
 * statistics instant and — only when the current version carries one — the note.
 */
internal fun p807Q2Flow3RowHeadline(row: LedgerEntryRow): String = row.kind.toString() + " · 统计 " + row.statisticsAt + (row.note?.let { note -> " · " + note } ?: "")

/** One transaction's posting lines, mirroring `flowRowPostingLineText` (`P503LedgerViewPresentation.kt:431-437`). */
internal fun p807Q2Flow3PostingLines(legs: List<TransactionDetailLeg>): List<String> =
    legs
        .map { leg ->
            leg.accountName + " " + p807Q2Flow3MinorText(leg.amount.minorUnits, leg.amount.currency.precision) + " " + leg.amount.currency.code
        }.sorted()

/** One refund candidate's row text, mirroring `refundCandidateRowText` (`P503RefundEntryPresentation.kt:92-102`). */
internal fun p807Q2Flow3CandidateRowText(candidate: ManualRefundCandidate): String {
    val precision = candidate.structuralCap.currency.precision
    val refundedMinor = candidate.structuralCap.minorUnits - candidate.remaining.minorUnits
    return listOf(
        p807Q2Flow3LocalDateText(candidate.occurredAt),
        candidate.categoryName,
        "支出腿金额 " + p807Q2Flow3MinorText(candidate.structuralCap.minorUnits, precision),
        "已退 " + p807Q2Flow3MinorText(refundedMinor, precision),
        "剩余 " + p807Q2Flow3MinorText(candidate.remaining.minorUnits, precision),
    ).joinToString(" · ")
}

/**
 * The balance face that one new transaction must produce: every seeded account keeps its currency
 * and precision and gains the exact sum of [legs]' minor units, in checked integer arithmetic. A leg
 * on an account the seed snapshot does not carry is a failure, not a new row, so the expectation is
 * always DERIVED from the pre-write read rather than guessed.
 */
internal fun p807Q2Flow3BalancesAfter(
    before: List<String>,
    legs: List<TransactionDetailLeg>,
): List<String> {
    val byAccount = LinkedHashMap<String, Triple<String, Int, Long>>()
    before.forEach { line ->
        val fields = line.split("|")
        check(fields.size == 4) { "a snapshot balance line must be accountId|code|precision|minor: [$line]" }
        byAccount[fields[0]] = Triple(fields[1], fields[2].toInt(), fields[3].toLong())
    }
    legs.forEach { leg ->
        val accountId = leg.accountId.value
        val current = checkNotNull(byAccount[accountId]) { "the new leg's account $accountId must already carry a seed balance" }
        check(current.first == leg.amount.currency.code && current.second == leg.amount.currency.precision) {
            "the new leg's currency must be the seeded currency of $accountId: leg=${leg.amount.currency} seeded=${current.first}/${current.second}"
        }
        byAccount[accountId] = Triple(current.first, current.second, Math.addExact(current.third, leg.amount.minorUnits))
    }
    return byAccount
        .map { (accountId, value) -> accountId + "|" + value.first + "|" + value.second + "|" + value.third }
        .sorted()
}

/**
 * The flow's authoritative read accessor: the launched app's ONE runtime owner, its operation lease,
 * and the production P806 reads plus the refund candidate read inside it.
 *
 * A missing query or port is a hard failure: this flow's every judgement depends on an authoritative
 * read, so a degraded graph must stop the case instead of silently passing.
 */
internal class P807Q2Flow3Ledger(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    private val tag = P807Q2Flow3Facts.PREFIX
    private val accessor = P807Q2Flow1Ledger(context)

    /** The formal + version face of the launched ledger, through the flow-1 accessor's own lease. */
    fun snapshot(): P807Q2Flow2Snapshot = accessor.read(::p807Q2Flow2ReadSnapshot)

    /**
     * [snapshot] in the shape a bounded POLL can repeat: the operation lease is acquired with
     * `tryLock` (`LedgerRuntimeOwner.kt:393-415`), so a read issued while the app is mid-operation
     * answers nothing instead of blocking or failing the case. `null` therefore means "not
     * observable yet", never "no transaction was added" — reading it as an empty ledger would turn
     * one busy moment of the app into a false negative about the write.
     */
    fun snapshotWhenLeasable(): P807Q2Flow2Snapshot? = ledgerProcessHolderFor(context)?.ledger?.withFacade { facade, _ -> p807Q2Flow2ReadSnapshot(facade) }

    /**
     * [addedTransaction] in the same repeatable shape: `null` when the lease is busy, while an
     * unreadable graph (InvalidState/Unavailable) still fails loudly inside the flow-1 helper — the
     * two outcomes this flow must never conflate are "could not look" and "nothing landed".
     */
    fun addedTransactionWhenLeasable(knownTransactionIds: Set<String>): TransactionDetail? = ledgerProcessHolderFor(context)?.ledger?.withFacade { facade, _ -> p807Q2Flow1AddedTransaction(facade, knownTransactionIds) }

    /** The one transaction the flow added, resolved by difference against the seed's known ids. */
    fun addedTransaction(knownTransactionIds: Set<String>): TransactionDetail = accessor.read { facade -> p807Q2Flow1AddedTransaction(facade, knownTransactionIds) }

    /** The first refund-candidate page (the same read the edit surface performs on opening). */
    fun refundCandidates(): ManualRefundCandidatesResult {
        val result =
            accessor.read { facade ->
                val port = checkNotNull(facade.readManualRefundCandidates) { "the launched graph must wire the refund candidate read" }
                port.readPage(facade.ledgerId, REFUND_CANDIDATE_PAGE_SIZE, 0)
            }
        when (result) {
            is ManualRefundCandidatesResult.Loaded ->
                log(
                    "$tag port candidates rows=" + result.candidates.size + " hasMore=" + result.hasMore + " " +
                        result.candidates.joinToString(";") { candidate ->
                            candidate.transactionId.value + "|" + candidate.remaining.minorUnits
                        },
                )
            ManualRefundCandidatesResult.Unavailable -> log("$tag port candidates unavailable")
        }
        return result
    }

    /** The folded report for [period], or a loud failure (a failed read is never read as a zero period). */
    fun report(period: P806Period): P806ReportView {
        val view =
            accessor.read { facade ->
                val query = checkNotNull(facade.p806ReportQuery) { "the launched graph must wire the report read" }
                when (val result = query.query(period)) {
                    is P806SnapshotResult.Success -> result.snapshot
                    is P806SnapshotResult.Failed -> error("the report read of [$period] failed: ${result.failure}")
                }
            }
        log(
            "$tag port report period=${view.period?.startDate}..${view.period?.endDateInclusive} " +
                "counts=${view.transactionCount}/${view.detailCount} " +
                view.currencies.joinToString(";") { row ->
                    row.currency.code + " income=" + row.ordinaryIncomeMinorUnits + " net=" + row.netExpenseMinorUnits +
                        " balance=" + row.balanceMinorUnits
                },
        )
        return view
    }

    /** The folded calendar of [month], or a loud failure. */
    fun calendar(month: YearMonth): P806CalendarView {
        val view =
            accessor.read { facade ->
                val query = checkNotNull(facade.p806CalendarQuery) { "the launched graph must wire the calendar read" }
                when (val result = query.query(month)) {
                    is P806SnapshotResult.Success -> result.snapshot
                    is P806SnapshotResult.Failed -> error("the calendar read of [$month] failed: ${result.failure}")
                }
            }
        log(
            "$tag port calendar month=${view.month} days=${view.days.size} " +
                "nonZeroDays=" + view.days.count { day -> day.transactionCount != 0 },
        )
        return view
    }

    /** The day-detail page of [window], or a loud failure. */
    fun drilldown(window: P806Window): P806DrilldownView {
        val view =
            accessor.read { facade ->
                val query = checkNotNull(facade.p806DrilldownQuery) { "the launched graph must wire the drilldown read" }
                when (val result = query.query(window)) {
                    is P806SnapshotResult.Success -> result.snapshot
                    is P806SnapshotResult.Failed -> error("the drilldown read of [$window] failed: ${result.failure}")
                }
            }
        log("$tag port drilldown totalCount=${view.totalCount} rows=${view.page.rows.size}")
        return view
    }
}

/**
 * The flow's refund-form, report, calendar and day-detail driver. The generic app discipline
 * (launch, seek, click, absence sampling, keyboard hide, failure dumps) stays in the frozen
 * [P807Q2Flow1Ui]; this class adds the label-anchored field input, the structurally anchored radio
 * rows, the effect-verified clicks those surfaces need, and the ONE-WAY activation ([activateOnce])
 * the ledger-judged submit requires.
 */
internal class P807Q2Flow3Ui(
    private val flow: P807Q2Flow1Ui,
    private val instrumentation: Instrumentation,
    private val log: (String) -> Unit,
) {
    private val tag = P807Q2Flow3Facts.PREFIX
    private val automation = instrumentation.uiAutomation

    // ---------------------------------------------------------------- navigation

    fun launch() {
        flow.launch()
    }

    fun awaitHome(): Boolean = flow.awaitHomeCapsule()

    /** Clicks the dock's HOME tab and proves the overview's own capsule renders. */
    fun goHome() {
        flow.clickApp(P807Q2Flow3Facts.TAB_HOME)
        check(awaitHome()) { "the HOME tab must land on the overview with its search capsule" }
    }

    /** The dock's record-plus opens the expense draft (`P503TabShell.kt:122-131`). */
    fun openExpenseEntry() {
        flow.clickApp(P807Q2Flow3Facts.NEW_ENTRY_DESCRIPTION)
        flow.awaitAppText(P807Q2Flow3Facts.ENTRY_TITLE_EXPENSE)
    }

    /** The edit surface's own 关闭 leaves the draft for the overview (`P503EditScreen.kt:248-252`). */
    fun closeEditor() {
        flow.clickApp(P807Q2Flow3Facts.CLOSE)
        check(awaitHome()) { "closing the editor must return to the overview" }
    }

    /** The dock's ANALYSIS tab, then the report region's own header. */
    fun openAnalysis() {
        flow.clickApp(P807Q2Flow3Facts.TAB_ANALYSIS)
        check(awaitAnywhereText(P807Q2Flow3Facts.REPORT_HEADER, seek = true)) {
            "the analysis tab must reach the report region's header [${P807Q2Flow3Facts.REPORT_HEADER}]"
        }
    }

    /** The HOME month row's one previous step, which lands on the domain's FIRST month. */
    fun homeMonthStepPrevious() {
        flow.clickApp(P807Q2Flow3Facts.MONTH_PREVIOUS)
    }

    /** The HOME calendar entry opens the monthly calendar (`P8HomeFlow.kt:276-278`). */
    fun openCalendar() {
        flow.clickApp(P807Q2Flow3Facts.CALENDAR_ENTRY)
        flow.awaitAppText(P807Q2Flow3Facts.CALENDAR_TITLE)
    }

    /** The calendar's OWN next-month step (`P503CalendarScreen.kt:79`). */
    fun calendarNextMonth() {
        flow.clickApp(P807Q2Flow3Facts.MONTH_NEXT)
    }

    // ---------------------------------------------------------------- refund form

    /**
     * Selects the refund type row. The radio carries no text, so the row is named by its own label
     * and the accepted effect is the surface's retitled header — the product re-renders the whole
     * field chain for a refund, so a title change is a real state change and not a paint.
     */
    fun selectRefundType(): Boolean =
        selectRadioVerified("selectRefundType", P807Q2Flow3Facts.TYPE_REFUND) {
            hasExactText(P807Q2Flow3Facts.ENTRY_TITLE_REFUND)
        }

    /**
     * Selects the one candidate's original-transaction row. The anchor is the row's own full label
     * (the candidate line): the row is `Row { RadioButton; Text(line) }`, so the radio is that
     * label's sibling — no coordinate is needed to name it, and the slot is only the fallback. The
     * accepted effect is that same radio's `checked` state, which mirrors
     * `draft.originalTransactionId == candidate.transactionId` (`P503EditScreen.kt:494-497`).
     */
    fun selectOriginalRow(rowText: String): Boolean = selectRadioVerified("selectOriginalRow", rowText) { radioCheckedFor(rowText) }

    /**
     * Selects the arrival account row and proves it took through the row's own radio: the option is
     * `Row { RadioButton(selected = option.accountId == draft.destinationAccountId); Text(name) }`
     * (`P503EditScreen.kt:514-521`), so the checked state IS the draft's destination. The amount
     * label's currency follows that choice (`P503EditScreen.kt:525-533`); the caller asserts that
     * label separately, because reaching it needs a scroll and a probe must not move the surface.
     */
    fun selectArrivalAccount(accountName: String): Boolean {
        val option = P807Q2Flow3Facts.arrivalOption(accountName)
        return selectRadioVerified("selectArrivalAccount", option) { radioCheckedFor(option) }
    }

    /** Whether the radio that belongs to [anchorText]'s row currently reports checked. */
    private fun radioCheckedFor(anchorText: String): Boolean {
        val label = flow.findAppNode { node -> flow.textOf(node) == anchorText } ?: return false
        return radioOfRow(label)?.isChecked == true
    }

    /** The report region's 自定义 chip reveals the two window inputs (`P503ReportRegion.kt:761-764`). */
    fun openCustomWindow(): Boolean =
        clickVerifiedExact("openCustomWindow", P807Q2Flow3Facts.KIND_CUSTOM) {
            awaitAnywhereText(P807Q2Flow3Facts.CUSTOM_START_LABEL, timeoutMillis = EFFECT_WAIT_MILLIS, seek = true)
        }

    /**
     * Commits the custom window and proves the landed period line: the region prints a resolved
     * window only for a landed read of the committed kind (`P503ReportRegion.kt:229-235`), so that
     * line is the effect, never the click. The effect wait is longer than a click's usual probe
     * because the landing is a database read: a shorter wait would let the retry re-commit an
     * in-flight window and re-enter the loading face.
     */
    fun commitCustomWindow(expectedPeriodLine: String): Boolean {
        hideKeyboard()
        return clickVerifiedExact("commitCustomWindow", P807Q2Flow3Facts.CUSTOM_SUBMIT) {
            awaitExactText(expectedPeriodLine, timeoutMillis = COMMIT_WAIT_MILLIS, seek = true)
        }
    }

    // ---------------------------------------------------------------- day detail

    /** Opens the day cell whose accessibility text is [description] and proves the drilldown landed. */
    fun openDayCell(description: String): Boolean = clickVerifiedExact("openDayCell", description) { hasExactText(P807Q2Flow3Facts.DRILLDOWN_TITLE) }

    /** Opens one flow row's read-only detail by its visible headline and proves the detail landed. */
    fun openRowDetail(headline: String): Boolean = clickVerifiedExact("openRowDetail", headline) { hasExactText(P807Q2Flow3Facts.DETAIL_TITLE) }

    /**
     * Clicks the surface's own 返回 and proves the caller's [effect]. The effect is the CALLER's
     * predicate on purpose: a return lands on a surface whose scroll may be restored, so the marker
     * that proves it must be one this step can see from wherever the return put it — and a wrong
     * marker would make the retries click a second 返回 that leaves the intended surface.
     */
    fun backVerified(
        step: String,
        effect: () -> Boolean,
    ): Boolean = clickVerifiedExact(step, P807Q2Flow3Facts.BACK, effect)

    // ---------------------------------------------------------------- text fields

    /**
     * Sets the field that belongs to [label] and VERIFIES the value landed, judging every read AFTER a
     * forced tree refresh. Compose renders an OutlinedTextField's label as its own text node while the
     * editable area is an `EditText` node, so the field is the editable node nearest to the label —
     * nearest rather than first, because a supporting text under the same field repeats the field's
     * word (the sampler precedent, `tools/python/android_fontscale/sampler.py` `find_field_center`).
     *
     * WHY EVERY JUDGEMENT IS REFRESHED. `ACTION_SET_TEXT` can be accepted AND applied while the client
     * accessibility cache keeps answering the PRE-write node for seconds: run `45615503912` polled the
     * still-empty field for the whole 5.5 s window after an accepted write, declared `landed=false`,
     * and fell back to key events. That fallback then typed into an editor the action had already
     * filled — and because the clear length came from the same stale node (zero) the value became
     * `99.9999.99`. Both halves are fixed here: a value is judged only after [refreshTreeForRead], and
     * the key-event channel empties the editor before typing, from a blind but harmless delete count
     * that no stale read can shrink. A field that already reads the target is a success, never a
     * rewrite (so a re-run of this step is idempotent).
     */
    fun setFieldByLabel(
        label: String,
        text: String,
    ): Boolean {
        // The IME from a previous field would sit over this one's tap target; the frozen helper only
        // presses BACK while an input-method window is actually rendered, so this is a no-op otherwise.
        runCatching { flow.hideKeyboardIfShown() }
        runCatching { flow.seekApp(label) }
        repeat(FIELD_INPUT_ATTEMPTS) { attempt ->
            refreshTreeForRead()
            val node = fieldForLabel(label)
            if (node == null) {
                log("$tag setField label=[$label] attempt=$attempt field=absent surface=${flow.surfaceTitle()}")
                flow.dumpUiSnapshot("setField:absent:$label")
                return false
            }
            val before = flow.textOf(node)
            log("$tag setField label=[$label] attempt=$attempt before=[$before] ${describeField(node)}")
            if (before == text) {
                log("$tag setField label=[$label] attempt=$attempt landed=true via=already-set value=[$text]")
                return true
            }
            runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
            val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            val accepted = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            refreshTreeForRead()
            runCatching { flow.settle() }
            val afterAction = fieldText(label)
            if (afterAction == text) {
                log("$tag setField label=[$label] attempt=$attempt accepted=$accepted landed=true via=action afterRefresh=[$afterAction] value=[$text]")
                return true
            }
            log("$tag setField label=[$label] attempt=$attempt accepted=$accepted landed=false via=action afterRefresh=[$afterAction]; clearing and typing")
            if (typeIntoField(label, text)) {
                log("$tag setField label=[$label] attempt=$attempt landed=true via=keys value=[$text]")
                return true
            }
        }
        flow.dumpUiSnapshot("setField:$label")
        return false
    }

    /**
     * The key-event channel: tap the field for focus, empty it, type the characters and verify after a
     * refresh. Clearing first is not cosmetic — the value an earlier action already wrote but the tree
     * could not show is exactly what turned `99.99` into `99.9999.99` in run `45615503912`. Returns
     * whether the field reads back exactly [text]; a field that already reads it is left untouched.
     */
    private fun typeIntoField(
        label: String,
        text: String,
    ): Boolean {
        if (currentFieldText(label) == text) {
            log("$tag setField label=[$label] keys skipped reason=already-set value=[$text]")
            return true
        }
        val node = fieldForLabel(label) ?: return false
        flow.tap(node)
        SystemClock.sleep(POLL_MILLIS)
        clearFieldToEmpty(label)
        val typed = typeTextEvents(text)
        log("$tag setField label=[$label] keys typed=$typed beforeVerify=[${fieldText(label)}] value=[$text]")
        return awaitFieldValue(label, text)
    }

    /**
     * Empties the field before typing. The delete count is `max(read length, [CLEAR_MIN_DELETES])` on
     * purpose: the read is the very thing that can lie on this stack, and deleting past the end of an
     * editor is a no-op, so the blind floor removes the whole `99.99` → `99.9999.99` class of failure
     * without needing a correct length.
     */
    private fun clearFieldToEmpty(label: String) {
        val existing = currentFieldText(label).orEmpty()
        val deletes = maxOf(existing.length, CLEAR_MIN_DELETES)
        log("$tag setField label=[$label] clear readLength=${existing.length} deletes=$deletes")
        clearFocusedField(deletes)
        SystemClock.sleep(POLL_MILLIS)
    }

    /**
     * Forces the tree current before a field value is judged. The client accessibility cache answers
     * the pre-write node until an event invalidates it (`AndroidScaleUi.kt:137-163`, the D-203
     * signature), and only a MISS goes through that helper's forcing passes (`root.refresh()` with
     * bypassCache, then the rate-limited D-208 reset, `:223-273`); a lookup of a PRESENT text is
     * cache-served and refreshes nothing, which is why the sentinel is a guaranteed miss.
     */
    private fun refreshTreeForRead() {
        flow.ui.has(REFRESH_SENTINEL)
    }

    /** The field's text from the CURRENT tree read (no refresh of its own), or null when absent. */
    private fun fieldText(label: String): String? = fieldForLabel(label)?.let { node -> flow.textOf(node) }

    /** The field's text after a forced refresh. */
    private fun currentFieldText(label: String): String? {
        refreshTreeForRead()
        return fieldText(label)
    }

    /** Hides the soft keyboard while the platform still holds one (the frozen flow-1 window-list test). */
    fun hideKeyboard(): Boolean = flow.hideKeyboardIfShown()

    /** The editable node nearest to any node carrying [label], or null when the screen has none. */
    private fun fieldForLabel(label: String): AccessibilityNodeInfo? {
        val nodes = flow.appNodes()
        val labels = nodes.filter { node -> flow.textOf(node).contains(label) }
        if (labels.isEmpty()) return null
        val fields = nodes.filter { node -> isEditable(node) }
        if (fields.isEmpty()) return null
        labels.firstOrNull { node -> isEditable(node) }?.let { return it }
        var best: AccessibilityNodeInfo? = null
        var bestGap = Int.MAX_VALUE
        var bestDistance = Int.MAX_VALUE
        labels.forEach { labelNode ->
            val labelBounds = flow.bounds(labelNode)
            fields.forEach { field ->
                val fieldBounds = flow.bounds(field)
                val gap = verticalGap(labelBounds, fieldBounds)
                val distance = abs(labelBounds.centerX() - fieldBounds.centerX())
                if (gap < bestGap || (gap == bestGap && distance < bestDistance)) {
                    best = field
                    bestGap = gap
                    bestDistance = distance
                }
            }
        }
        return best
    }

    /** Zero when the two rects share a row band, else the distance between the facing edges. */
    private fun verticalGap(
        label: Rect,
        field: Rect,
    ): Int = if (label.top < field.bottom && field.top < label.bottom) 0 else minOf(abs(label.top - field.bottom), abs(field.top - label.bottom))

    /** Whether [node] is a writable field on this stack. */
    private fun isEditable(node: AccessibilityNodeInfo): Boolean = node.isEditable || node.className?.toString()?.contains("EditText") == true

    /**
     * Waits until the field belonging to [label] carries [expected], forcing the tree current before
     * EVERY poll: the editor's text lives in the client cache, so one stale read would otherwise turn
     * a landed write into a false negative (run `45615503912` read the empty pre-write node for the
     * whole window).
     */
    private fun awaitFieldValue(
        label: String,
        expected: String,
        timeoutMillis: Long = FIELD_SETTLE_TIMEOUT_MILLIS,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            refreshTreeForRead()
            if (fieldForLabel(label)?.let { node -> flow.textOf(node) } == expected) return true
            SystemClock.sleep(POLL_MILLIS)
        }
        return false
    }

    /** The facts the input path needs: what the node is, what it shows and what it accepts. */
    private fun describeField(node: AccessibilityNodeInfo): String =
        "class=" + node.className + " editable=" + node.isEditable + " focusable=" + node.isFocusable +
            " focused=" + node.isFocused + " text=[" + flow.textOf(node) + "] bounds=" + flow.bounds(node) +
            " actions=" + node.actionList.map { action -> action.id }

    /** Empties the focused editor: move the caret to the end, then delete [length] characters. */
    private fun clearFocusedField(length: Int) {
        if (length <= 0) return
        runCatching { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MOVE_END) }
        repeat(length) { runCatching { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DEL) } }
    }

    /** Injects [text] as hardware key events (the platform's virtual keyboard map); no IME needed. */
    private fun typeTextEvents(text: String): Boolean =
        runCatching {
            KeyCharacterMap
                .load(KeyCharacterMap.VIRTUAL_KEYBOARD)
                .getEvents(text.toCharArray())
                .forEach { event -> instrumentation.sendKeySync(event) }
            true
        }.getOrDefault(false)

    // ---------------------------------------------------------------- assertions read off one tree

    /** Whether one visible node carries [text] as its exact text OR its exact description. */
    fun hasExactText(text: String): Boolean = flow.findAppNode { node -> matchesExact(node, text) } != null

    /**
     * Bounded wait for [text] as one node's exact text or description. With [seek], each poll pays
     * one forward seek first, so a target below the fold is reached instead of timed out.
     */
    fun awaitExactText(
        text: String,
        timeoutMillis: Long = NODE_TIMEOUT_MILLIS,
        seek: Boolean = false,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (hasExactText(text)) return true
            if (seek) runCatching { flow.seekApp(text) }
            SystemClock.sleep(POLL_MILLIS)
        }
        return false
    }

    /** Whether any visible text CONTAINS [text] (the selector labels merge their descendants). */
    fun hasAnywhereText(text: String): Boolean = visibleTexts().any { line -> line.contains(text) }

    /** Bounded wait for any visible text containing [text], with one forward seek per poll. */
    fun awaitAnywhereText(
        text: String,
        timeoutMillis: Long = NODE_TIMEOUT_MILLIS,
        seek: Boolean = true,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (hasAnywhereText(text)) return true
            if (seek) runCatching { flow.seekApp(text) }
            SystemClock.sleep(POLL_MILLIS)
        }
        return false
    }

    /** Whether one visible node's text or description STARTS WITH [prefix] (the frozen flow-1 rule). */
    fun hasPrefixText(prefix: String): Boolean = flow.hasAppText(prefix)

    /** Bounded wait for a prefix hit, with one forward seek per poll when [seek]. */
    fun awaitPrefixText(
        prefix: String,
        timeoutMillis: Long = NODE_TIMEOUT_MILLIS,
        seek: Boolean = false,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (hasPrefixText(prefix)) return true
            if (seek) runCatching { flow.seekApp(prefix) }
            SystemClock.sleep(POLL_MILLIS)
        }
        return false
    }

    /** A bounded negative assertion over the visible tree (the frozen flow-1 absence sampler). */
    fun awaitNoText(prefix: String): String? = flow.awaitNoAppText(prefix)

    /** Every visible node's text, used for the containing assertions and the failure dumps. */
    fun visibleTexts(): List<String> = flow.appNodes().map { node -> flow.textOf(node) }.filter { text -> text.isNotBlank() }

    /** The current surface's lead texts (the surface's own evidence on a miss). */
    fun surfaceTitle(): String = flow.surfaceTitle()

    fun seek(text: String) {
        flow.seekApp(text)
    }

    /** Scrolls the current surface back until [marker] is visible again (a top-anchored affordance). */
    fun scrollBackTo(marker: String) {
        flow.scrollBackTo(marker)
    }

    fun settle() {
        flow.settle()
    }

    /** Writes the failure-time picture (chunked into logcat) and returns it whole for the message. */
    fun dump(label: String): String = flow.dumpUiSnapshot(label)

    // ---------------------------------------------------------------- verified clicks

    /**
     * Clicks the node whose exact text or description is [text] and proves [effect]. The target is
     * reached with one bounded forward seek first (these surfaces scroll), and the click itself runs
     * through [clickVerified], so a dropped action is retried instead of trusted against a stale tree.
     */
    fun clickVerifiedExact(
        step: String,
        text: String,
        effect: () -> Boolean,
    ): Boolean {
        refocusAnchor(text)
        if (!hasExactText(text)) {
            val report = anchorReport(step, text, null)
            flow.dumpUiSnapshot("$step:absent")
            log("$tag click step=$step target=[$text] absent " + report)
            return false
        }
        val clicked = clickVerified(step, text, { flow.findAppNode { node -> matchesExact(node, text) } }, { null }, effect)
        if (!clicked) {
            log("$tag click-failed step=$step target=[$text] " + anchorReport(step, text, null))
        }
        return clicked
    }

    // ---------------------------------------------------------------- one-way activations

    /**
     * Activates the action whose exact text or description is [text] AT MOST ONCE and reports whether
     * the action was actually SENT. The rule belongs to the ledger: a confirm that may already have
     * written must never be clicked twice, so this primitive repeats only the LOOKUP, never the
     * action — a re-read of the screen is safe, a second submit is a second write.
     *
     * The two evidences are deliberately separate layers. The ON-SCREEN layer is a target found after
     * one bounded re-focus whose bounds lie inside the display AND the app window. The ACTION-SENT
     * layer is the [clickVerified] family's own channel answer: ACTION_CLICK accepted by the target or
     * one of its clickable ancestors ([clickSemantic]); only a REFUSED semantic action escalates to one
     * coordinate tap on that same on-screen centre, because tapping after an ACCEPTED click is exactly
     * the second write this method exists to prevent. `false` means NO action was sent at all: the
     * caller fails on that instead of retrying, which is the fail-closed side of the rule.
     */
    fun activateOnce(
        step: String,
        text: String,
    ): Boolean {
        repeat(ACTIVATION_LOOKUP_ATTEMPTS) { attempt ->
            refocusAnchor(text)
            val target = flow.findAppNode { node -> matchesExact(node, text) }
            val bounds = target?.let { node -> flow.bounds(node) }
            if (target != null && bounds != null && isOnScreen(bounds)) {
                val point = bounds.centerX() to bounds.centerY()
                val accepted = clickSemantic(target)
                log("$tag activate step=$step target=[$text] channel=semantic accepted=$accepted onScreen=true bounds=$bounds")
                if (accepted) return true
                val tapped =
                    try {
                        tapAt(point)
                        true
                    } catch (failure: IllegalStateException) {
                        log("$tag activate step=$step target=[$text] channel=coordinate-target sent=false reason=[" + failure.message + "]")
                        false
                    }
                if (tapped) {
                    log("$tag activate step=$step target=[$text] channel=coordinate-target sent=true point=(" + point.first + "," + point.second + ")")
                    return true
                }
                flow.dumpUiSnapshot("$step:not-injected")
                return false
            }
            log("$tag activate step=$step attempt=$attempt target=[$text] sent=false reason=not-on-screen bounds=$bounds")
            SystemClock.sleep(POLL_MILLIS)
        }
        flow.dumpUiSnapshot("$step:absent")
        log("$tag activate step=$step target=[$text] sent=false reason=never-on-screen " + anchorReport(step, text, null))
        return false
    }

    /**
     * Selects the radio of [anchorText]'s row and proves [effect]. The row is
     * `Row { RadioButton; Text(anchor) }`, so the radio is the anchor's sibling inside the nearest
     * ancestor that holds a checkable node on the anchor's band — the one immediately LEFT of the
     * anchor when several rows share that band (the type choice list and any wrapped row); the
     * derived slot is the fallback for a tree that refuses to name that node at all, and [effect] is
     * the only success test either way.
     */
    private fun selectRadioVerified(
        step: String,
        anchorText: String,
        effect: () -> Boolean,
    ): Boolean {
        val locate = { flow.findAppNode { node -> flow.textOf(node) == anchorText }?.let(::radioOfRow) }
        val slot = { flow.findAppNode { node -> flow.textOf(node) == anchorText }?.let(::radioSlotLeftOf) }
        val selected = clickVerified(step, anchorText, locate, slot, effect)
        if (!selected) {
            val label = flow.findAppNode { node -> flow.textOf(node) == anchorText }
            log("$tag select-failed step=$step anchor=[$anchorText] " + anchorReport(step, anchorText, label))
        }
        return selected
    }

    /**
     * Re-establishes the viewport and the tree for ONE round of a click. Five bounded steps, each
     * with the reason this stack needs it:
     * 1. when the anchor is NOT in the visible tree it may be ABOVE the viewport (an earlier label
     *    seek scrolled the form down), so the backward seek runs before the forward one;
     * 2. a forward seek then puts the anchor on screen (the frozen helper's own bounded scroll);
     * 3. a small out-and-back scroll makes the app re-lay out and emits accessibility events — on this
     *    stack the client AccessibilityCache is invalidated by event delivery, so a stalled feed keeps
     *    serving one stale tree (`AndroidScaleUi.kt:137-163`, the D-203 signature);
     * 4. a lookup of an ABSENT text then takes that helper's second pass (`root.refresh()` with
     *    bypassCache) and, when its rate limit allows, the D-208 cache reset (`:223-273`); a lookup of
     *    a PRESENT text is served from the cache and refreshes nothing, which is why the sentinel is a
     *    guaranteed miss;
     * 5. a trailing seek undoes any drift the out-and-back left on a surface that scrolls one way only.
     */
    private fun refocusAnchor(anchorText: String) {
        if (!flow.hasAppText(anchorText)) {
            runCatching { flow.scrollBackTo(anchorText) }
        }
        runCatching { flow.seekApp(anchorText) }
        runCatching { flow.ui.scroll(forward = true, fraction = NUDGE_FRACTION) }
        runCatching { flow.ui.scroll(forward = false, fraction = NUDGE_FRACTION) }
        flow.ui.has(REFRESH_SENTINEL)
        runCatching { flow.seekApp(anchorText) }
    }

    /**
     * Clicks one target and proves the screen took it, ROUND BY ROUND against a CURRENT tree: each
     * round first re-establishes the viewport ([refocusAnchor]), then reads the target again, then
     * only taps a target whose own bounds are ON SCREEN. A node with stale or clipped bounds is never
     * hard-tapped — the round records why and moves on — so the coordinate fallback stays fail-closed
     * and [effect] remains the only success test.
     */
    private fun clickVerified(
        step: String,
        anchorText: String,
        locate: () -> AccessibilityNodeInfo?,
        slot: () -> Pair<Int, Int>?,
        effect: () -> Boolean,
    ): Boolean {
        repeat(SELECTION_ATTEMPTS) { round ->
            refocusAnchor(anchorText)
            val target = locate()
            val targetBounds = target?.let { node -> flow.bounds(node) }
            val onScreen = targetBounds != null && isOnScreen(targetBounds)
            val accepted = target?.let(::clickSemantic)
            log(
                "$tag select step=$step round=$round channel=semantic actionClick=$accepted onScreen=$onScreen target=" +
                    (target?.let { node -> describe(node) } ?: "absent"),
            )
            if (probeEffect(step, round, "semantic", locate, effect)) return true
            if (target != null && targetBounds != null && onScreen) {
                val point = targetBounds.centerX() to targetBounds.centerY()
                tapAt(point)
                log("$tag select step=$step round=$round channel=coordinate-target point=(" + point.first + "," + point.second + ")")
                if (probeEffect(step, round, "coordinate-target", locate, effect)) return true
            } else {
                log(
                    "$tag select step=$step round=$round channel=coordinate-target skipped reason=target-not-on-screen bounds=$targetBounds " +
                        "display=${displaySize()} window=${appWindowBounds()}",
                )
            }
            val fallbackSlot = slot()
            if (fallbackSlot != null && isPointOnScreen(fallbackSlot)) {
                tapAt(fallbackSlot)
                log("$tag select step=$step round=$round channel=coordinate-slot point=(" + fallbackSlot.first + "," + fallbackSlot.second + ")")
                if (probeEffect(step, round, "coordinate-slot", locate, effect)) return true
            } else if (fallbackSlot != null) {
                log("$tag select step=$step round=$round channel=coordinate-slot skipped reason=slot-not-on-screen point=(" + fallbackSlot.first + "," + fallbackSlot.second + ")")
            }
            flow.dumpUiSnapshot("$step:round$round")
            flow.settle()
        }
        log("$tag select step=$step never took effect; surface=${flow.surfaceTitle()}")
        return false
    }

    /**
     * Polls [effect] for at most [EFFECT_TIMEOUT_MILLIS] against a tree that is periodically forced
     * current (one absent-text lookup every [PROBE_REFRESH_EVERY] polls, i.e. roughly twice a second),
     * recording the target's `checked` and the effect value at [PROBE_FIRST_MILLIS] and
     * [PROBE_SECOND_MILLIS]: checked at both says the click never reached the reducer, checked once and
     * gone says the product applied it and then lost it (a product finding the driver must not retry
     * around). Without the refresh a landed effect can be invisible for the whole poll window because
     * the client cache still serves the pre-click tree.
     */
    private fun probeEffect(
        step: String,
        round: Int,
        channel: String,
        locate: () -> AccessibilityNodeInfo?,
        effect: () -> Boolean,
    ): Boolean {
        val started = SystemClock.elapsedRealtime()
        var readFirst = false
        var readSecond = false
        var sawChecked = false
        var polls = 0
        while (true) {
            val elapsed = SystemClock.elapsedRealtime() - started
            if (polls % PROBE_REFRESH_EVERY == 0) flow.ui.has(REFRESH_SENTINEL)
            polls++
            if (!readFirst && elapsed >= PROBE_FIRST_MILLIS) {
                readFirst = true
                sawChecked = probeReading(step, round, channel, locate, effect, elapsed)
            }
            if (!readSecond && elapsed >= PROBE_SECOND_MILLIS) {
                readSecond = true
                sawChecked = probeReading(step, round, channel, locate, effect, elapsed) || sawChecked
            }
            if (effect()) {
                log("$tag select step=$step round=$round channel=$channel at=${elapsed}ms effect=true")
                return true
            }
            if (elapsed >= EFFECT_TIMEOUT_MILLIS) break
            SystemClock.sleep(PROBE_POLL_MILLIS)
        }
        val verdict = if (sawChecked) "checked-then-lost" else "click-not-applied"
        log("$tag select step=$step round=$round channel=$channel verdict=$verdict")
        return false
    }

    /** One probe reading: logs the target's `checked` state and the effect value, returns `checked`. */
    private fun probeReading(
        step: String,
        round: Int,
        channel: String,
        locate: () -> AccessibilityNodeInfo?,
        effect: () -> Boolean,
        elapsedMs: Long,
    ): Boolean {
        val checked = locate()?.isChecked == true
        log("$tag select step=$step round=$round channel=$channel at=${elapsedMs}ms checked=$checked effect=" + effect())
        return checked
    }

    /**
     * The semantic channel with its answer kept: ACTION_CLICK on [node], then on up to
     * [CLICK_ANCESTOR_DEPTH] clickable ancestors. `false` is an observation (the tree refused the
     * action), never a failure by itself.
     */
    private fun clickSemantic(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(CLICK_ANCESTOR_DEPTH) {
            val candidate = current ?: return false
            val usable = candidate.isEnabled && candidate.isVisibleToUser
            if (usable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = candidate.parent
        }
        return false
    }

    /** Injects one touchscreen tap at [point] (the D-217 coordinate path the frozen drivers use). */
    private fun tapAt(point: Pair<Int, Int>) {
        val (x, y) = point
        val down = SystemClock.uptimeMillis()
        val events =
            listOf(
                MotionEvent
                    .obtain(down, down, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
                    .apply { source = InputDevice.SOURCE_TOUCHSCREEN },
                MotionEvent
                    .obtain(down, down + TOUCH_HOLD_MILLIS, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)
                    .apply { source = InputDevice.SOURCE_TOUCHSCREEN },
            )
        try {
            events.forEach { event ->
                check(automation.injectInputEvent(event, true)) { "coordinate tap injection refused at ($x, $y)" }
            }
        } finally {
            events.forEach { event -> event.recycle() }
        }
        SystemClock.sleep(180)
    }

    /**
     * The radio that belongs to [label]'s own row, found by STRUCTURE: the label's sibling inside
     * the nearest ancestor that holds a checkable node on the label's band. When SEVERAL radios share
     * that band — one row per choice, all on one line (the type choice list, and any row that wraps
     * beside it) — a Y-only tie-break returns the FIRST of them, which is a different row's radio:
     * run `45610482800` clicked the wrong radio that way and every channel stayed inert. The row's own
     * radio is the one immediately LEFT of the label, so the horizontal gap decides and the band only
     * filters candidates.
     */
    private fun radioOfRow(label: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val band = flow.bounds(label)
        var current: AccessibilityNodeInfo? = label.parent
        repeat(ANCESTOR_DEPTH) {
            val container = current ?: return null
            val radios =
                collectNodes(container).filter { node ->
                    node != label && node.isVisibleToUser && isRadioNode(node) && onBand(node, band)
                }
            if (radios.size == 1) return radios.single()
            if (radios.isNotEmpty()) return nearestRadioLeftOf(radios, band)
            current = container.parent
        }
        return null
    }

    /**
     * Among several radios sharing [band], the one that belongs to the anchor's own row: the smallest
     * horizontal gap between the radio's right edge and the anchor's left edge, with a radio that sits
     * RIGHT of the anchor (impossible for the frozen radio-then-label row order) pushed behind every
     * left one by [RADIO_RIGHT_OF_ANCHOR_PENALTY]. The vertical distance only separates rows that a
     * re-measured band lets in.
     */
    private fun nearestRadioLeftOf(
        radios: List<AccessibilityNodeInfo>,
        band: Rect,
    ): AccessibilityNodeInfo? {
        val nearest =
            radios
                .map { node -> node to flow.bounds(node) }
                .filter { (_, rect) -> rect.width() > 0 }
                .minByOrNull { (_, rect) ->
                    val gap = band.left - rect.right
                    val horizontal = if (gap >= 0) gap else RADIO_RIGHT_OF_ANCHOR_PENALTY + (-gap)
                    horizontal + abs(rect.centerY() - band.centerY())
                }
        return nearest?.first
    }

    /** Whether [node] is radio-like: checkable, selected, or the platform's radio/check class. */
    private fun isRadioNode(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        return node.isCheckable ||
            node.isChecked ||
            node.isSelected ||
            className.endsWith("RadioButton") ||
            className.endsWith("CheckBox")
    }

    /** Whether [node]'s centre sits on [band]'s row, with the slack a re-measured row needs. */
    private fun onBand(
        node: AccessibilityNodeInfo,
        band: Rect,
    ): Boolean {
        val candidate = flow.bounds(node)
        return candidate.centerY() in (band.top - BAND_SLACK_PX)..(band.bottom + BAND_SLACK_PX) && candidate.width() > 0
    }

    /**
     * The radio's screen point derived from [label]'s own bounds: one radio column left of the label,
     * on the label's vertical centre. The row's child order is fixed radio-then-label, and a
     * Material3 radio's touch target is [RADIO_SLOT_DP] dp wide. This is the fallback for a tree that
     * will not name the radio node; the caller accepts it only when the click's own effect flips, so
     * a tap on the wrong pixel fails the leg instead of passing it.
     */
    private fun radioSlotLeftOf(label: AccessibilityNodeInfo): Pair<Int, Int>? {
        val band = flow.bounds(label)
        val density =
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext
                .resources
                .displayMetrics
                .density
        val x = band.left - (RADIO_SLOT_DP * density).toInt()
        return if (x > 0) x to band.centerY() else null
    }

    /** One click target's exact text-or-description match. */
    private fun matchesExact(
        node: AccessibilityNodeInfo,
        text: String,
    ): Boolean = node.isVisibleToUser && (flow.textOf(node) == text || flow.descriptionOf(node) == text)

    /** The display's pixel size, as the driver reads it before trusting any bounds. */
    private fun displaySize(): String {
        val metrics = instrumentation.targetContext.resources.displayMetrics
        return metrics.widthPixels.toString() + "x" + metrics.heightPixels
    }

    /** The app window's screen rect, or null when the window list cannot name it. */
    private fun appWindowBounds(): Rect? =
        automation.windows
            .firstOrNull { window -> runCatching { window.root?.packageName?.toString() }.getOrNull() == instrumentation.targetContext.packageName }
            ?.let { window -> Rect().also { rect -> window.getBoundsInScreen(rect) } }

    /**
     * Whether [rect] is a non-empty rectangle that lies inside the display AND intersects the app
     * window. The window test is the one that matters: a node whose bounds were computed for a layout
     * the window no longer shows has coordinates that inject taps into nothing.
     */
    private fun isOnScreen(rect: Rect): Boolean {
        if (rect.width() <= 0 || rect.height() <= 0) return false
        val metrics = instrumentation.targetContext.resources.displayMetrics
        if (rect.left >= metrics.widthPixels || rect.top >= metrics.heightPixels || rect.right <= 0 || rect.bottom <= 0) return false
        val window = appWindowBounds() ?: return true
        return rect.left < window.right && window.left < rect.right && rect.top < window.bottom && window.top < rect.bottom
    }

    /** Whether one screen point would land inside the display and the app window. */
    private fun isPointOnScreen(point: Pair<Int, Int>): Boolean = isOnScreen(Rect(point.first, point.second, point.first + 1, point.second + 1))

    /**
     * The self-evidence of a failed anchor, extended for run `45610482800`: the anchor's own shape and
     * bounds, the display and window geometry the taps are validated against, EVERY radio the tree
     * reports on the anchor's band (class/checkable/checked/clickable/bounds — the data that shows a
     * band-mate was clicked instead), the resolved target, and one refresh-then-reread comparison of
     * that target's bounds so a stale tree names itself. Truncation is MARKED.
     */
    private fun anchorReport(
        step: String,
        anchorText: String,
        label: AccessibilityNodeInfo?,
    ): String {
        val lines = ArrayList<String>()
        lines.add("anchor-report step=$step anchor=[$anchorText]")
        lines.add("display=" + displaySize() + " window=" + (appWindowBounds() ?: "unknown"))
        if (label == null) {
            lines.add("anchor=ABSENT (no visible node carries that exact text)")
        } else {
            lines.add("anchor=" + describe(label))
            lines.add("anchor-bounds=" + flow.bounds(label))
            val band = flow.bounds(label)
            val bandRadios = flow.appNodes().filter { node -> isRadioNode(node) && onBand(node, band) }
            lines.add("band-radios count=" + bandRadios.size + " band=" + band)
            bandRadios.forEach { node -> lines.add("  radio " + describe(node)) }
            val target = radioOfRow(label)
            lines.add("target=" + (target?.let { node -> describe(node) } ?: "ABSENT"))
            if (target != null) {
                val before = flow.bounds(target)
                flow.ui.has(REFRESH_SENTINEL)
                val after = radioOfRow(label)?.let { node -> flow.bounds(node) }
                lines.add("refresh-compare before=" + before + " after=" + after + " changed=" + (after != before))
            }
            val parent = label.parent
            if (parent == null) {
                lines.add("parent=ABSENT (the client tree answered no parent for the anchor)")
            } else {
                val subtree = collectNodes(parent)
                lines.add("parent-subtree nodes=" + subtree.size)
                subtree.forEach { node -> lines.add("  " + describe(node)) }
            }
        }
        val texts = visibleTexts().distinct()
        lines.add("visible-texts count=" + texts.size + " [" + texts.joinToString(" ¦ ") + "]")
        return reportOf(lines)
    }

    /** Joins [lines], cutting at [MAX_REPORT_LINES] with an explicit marker instead of stopping silently. */
    private fun reportOf(lines: List<String>): String {
        if (lines.size <= MAX_REPORT_LINES) return lines.joinToString("\n")
        val kept = lines.take(MAX_REPORT_LINES)
        return (kept + "… truncated at " + MAX_REPORT_LINES + " of " + lines.size + " lines").joinToString("\n")
    }

    private fun describe(node: AccessibilityNodeInfo): String =
        "class=" + node.className + " clickable=" + node.isClickable + " enabled=" + node.isEnabled +
            " checkable=" + node.isCheckable + " checked=" + node.isChecked + " selected=" + node.isSelected +
            " bounds=" + flow.bounds(node) + " text=[" + flow.textOf(node) + "] desc=[" + flow.descriptionOf(node) + "]"

    /** Breadth-first walk of [root], bounded (the frozen flow-1 collector's shape). */
    private fun collectNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && result.size < MAX_TREE_NODES) {
            val node = queue.removeFirst()
            result.add(node)
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::add)
            }
        }
        return result
    }

    private companion object {
        /** How many ancestors a structural anchor may climb (the frozen anchor's bound). */
        const val ANCESTOR_DEPTH = 5

        /** The bounded tree walk of one anchor lookup. */
        const val MAX_TREE_NODES = 2_000

        /** The vertical slack one anchor tolerates around its row band (px). */
        const val BAND_SLACK_PX = 16

        /** The radio centre's offset from its label's left edge, in dp (a Material3 radio touch target). */
        const val RADIO_SLOT_DP = 24

        /** Pushes a radio that sits RIGHT of its anchor behind every radio that sits left of it. */
        const val RADIO_RIGHT_OF_ANCHOR_PENALTY = 10_000

        /** The out-and-back scroll step that makes the app re-lay out (a fraction of the viewport). */
        const val NUDGE_FRACTION = 0.15f

        /** A text no visible node can carry: its lookup is the only in-process forced tree refresh. */
        const val REFRESH_SENTINEL = "\u0000P807-Q2FLOW3-refresh"

        /** How many effect polls pass between two forced tree refreshes (about twice a second). */
        const val PROBE_REFRESH_EVERY = 2

        /** How long one injected tap holds the touch down. */
        const val TOUCH_HOLD_MILLIS = 60L

        /** The most lines one anchor report may carry before it is cut with a marker. */
        const val MAX_REPORT_LINES = 60

        /** The selection/click retries (the frozen flow-1 `SELECTION_CLICK_ATTEMPTS` value). */
        const val SELECTION_ATTEMPTS = 3

        /** How many times a one-way activation may LOOK for its target before it fails un-sent. */
        const val ACTIVATION_LOOKUP_ATTEMPTS = 3

        /** How many field-input escalations one value may take. */
        const val FIELD_INPUT_ATTEMPTS = 3

        /** How long one written field value may take to read land. */
        const val FIELD_SETTLE_TIMEOUT_MILLIS = 5_000L

        /** The blind delete floor of the key-event channel: deleting past an editor's end is a no-op. */
        const val CLEAR_MIN_DELETES = 24

        /** The poll interval used while proving a selection took effect (ms). */
        const val PROBE_POLL_MILLIS = 250L

        /** The first post-click reading of the target's checked state (ms). */
        const val PROBE_FIRST_MILLIS = 300L

        /** The second post-click reading (ms): a pick checked here and gone later was reset. */
        const val PROBE_SECOND_MILLIS = 2_000L

        /** How long one channel's effect may take before the next channel is tried (ms). */
        const val EFFECT_TIMEOUT_MILLIS = 4_000L

        /** How many clickable ancestors the semantic channel may climb (the `clickNode` bound). */
        const val CLICK_ANCESTOR_DEPTH = 7

        /** How long one awaited text may take to appear. */
        const val NODE_TIMEOUT_MILLIS = 30_000L

        /** The bounded text wait one click's effect probe pays, so a probe never blocks a channel. */
        const val EFFECT_WAIT_MILLIS = 2_000L

        /** The bounded wait one committed report window's landed line may take (a database read). */
        const val COMMIT_WAIT_MILLIS = 10_000L

        /** The UI poll interval (the frozen flow-1 `POLL_MILLIS` value). */
        const val POLL_MILLIS = 500L
    }
}
