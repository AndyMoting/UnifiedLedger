package com.unifiedledger.android

import com.unifiedledger.application.WidgetMonthlySnapshot
import com.unifiedledger.application.WidgetMonthlySnapshotStatus
import com.unifiedledger.ui.WidgetSnapshotOutcome
import kotlinx.datetime.YearMonth

/*
 * P7-09 09.C widget render decisions (D-225 Q20; spec sections 6.1/6.4/6.5).
 *
 * EVERY display and privacy decision of the monthly widget is a PURE function here, so it is
 * JVM-testable without Robolectric (the `LedgerProcessHolder`/`ExternalNavigationIntake` seam
 * discipline). The Android `AppWidgetProvider` only translates these values into `RemoteViews`
 * text/`contentDescription` and a `PendingIntent`; it decides nothing.
 *
 * The hidden-state payload set of spec section 6.5 is enforced HERE, by construction: when the
 * per-widget visibility setting is off, [WidgetViewState.amountLines] is EMPTY and every other
 * string is built from the month label, the status, the update time and the host notice only — the
 * amount strings are never even formatted. The click `PendingIntent` carries the generic route
 * token only (the widget id rides on the toggle broadcast, never on the click intent) and the toggle
 * `PendingIntent` carries the widget id only — neither carries an amount, a ledger id or an account
 * name. A test asserts each of those payloads against a snapshot that DOES carry amounts.
 */

/** The generic click route the widget body reuses (spec sections 6.1/6.5; J5). */
internal enum class WidgetClickRoute {
    /** Open the app on its current month (the app's default 看账 surface). */
    OPEN_CURRENT_MONTH,
}

/** The widget-id extra key. The id is a host-assigned int, never personal data. */
internal const val WIDGET_ID_EXTRA: String = "appWidgetId"

/**
 * The complete, display-safe payload of one widget render (spec sections 6.4/6.5). Everything the
 * provider writes into `RemoteViews`/`contentDescription`/`PendingIntent` comes from this value, so
 * the hidden-state hard constraint is asserted on ONE object instead of scattered call sites.
 */
internal data class WidgetViewState(
    /** The month this state describes (`YYYY-MM`); never an amount. */
    val month: YearMonth,
    /** The status line (可用/失效/不可用); never an amount. */
    val statusText: String,
    /**
     * The amount lines; EMPTY whenever the setting is hidden or the snapshot is not available, so
     * a hidden widget structurally has nothing to render (spec section 6.5(a)).
     */
    val amountLines: List<String>,
    /** The update-time line, or null when no `asOf` is known; never an amount. */
    val updateText: String?,
    /**
     * P7-09 09.C (spec section 6.5: "需提示系统桌面/锁屏由 host 控制，隐藏开关不能撤回历史截图"):
     * the always-present host-boundary notice. It is amount-free by construction, so it can be shown
     * in the hidden state too.
     */
    val hostNoticeText: String,
    /** The accessibility text; equals the visible lines and never contains an amount while hidden. */
    val contentDescription: String,
    /** The generic click route; never an amount and never a ledger identifier. */
    val clickRoute: WidgetClickRoute,
    /** The host-assigned widget id carried alongside the route. */
    val widgetId: Int,
    /** The config revision this state was computed under (spec section 6.4 landing check). */
    val revision: Long,
    /** The runtime generation this state was computed under (spec section 6.4 landing check). */
    val generation: Int,
)

/**
 * Builds the display payload for one widget from the lease-scoped read outcome and the widget's
 * current configuration (spec sections 6.1/6.4/6.5).
 *
 * [generation] travels from the read (the runtime generation it ran under) so the provider's landing
 * hop can compare generation and revision before writing anything (spec section 6.4). When the
 * outcome is not a successful read, or the setting is hidden, a snapshot's amounts are NEVER put into
 * any string.
 */
internal fun widgetViewState(
    month: YearMonth,
    outcome: WidgetSnapshotOutcome,
    config: LedgerWidgetConfig,
    generation: Int,
    widgetId: Int,
): WidgetViewState {
    val snapshot = (outcome as? WidgetSnapshotOutcome.Read)?.snapshot
    val statusText = widgetStatusText(outcome)
    val updateText = snapshot?.asOf?.let { WIDGET_UPDATED_PREFIX + it.toString() }
    val amountLines = widgetAmountLines(snapshot, config)
    val lines =
        listOfNotNull(month.toString(), statusText) + amountLines + listOfNotNull(updateText, WIDGET_HOST_NOTICE)
    return WidgetViewState(
        month = month,
        statusText = statusText,
        amountLines = amountLines,
        updateText = updateText,
        hostNoticeText = WIDGET_HOST_NOTICE,
        contentDescription = lines.joinToString(WIDGET_LINE_SEPARATOR),
        clickRoute = WidgetClickRoute.OPEN_CURRENT_MONTH,
        widgetId = widgetId,
        revision = config.revision,
        generation = generation,
    )
}

/**
 * The status line for one read outcome. Each failure state has explicit copy and NEVER renders an
 * amount (spec section 6.4: "失败不得伪装零值"; P709-A05/A06): a not-Ready runtime and an unwired
 * surface both guide the user to open the app instead of showing a fabricated `0.00`.
 */
internal fun widgetStatusText(outcome: WidgetSnapshotOutcome): String =
    when (outcome) {
        is WidgetSnapshotOutcome.Read ->
            when (outcome.snapshot.status) {
                WidgetMonthlySnapshotStatus.AVAILABLE -> WIDGET_STATUS_AVAILABLE
                WidgetMonthlySnapshotStatus.INVALID -> WIDGET_STATUS_INVALID
                WidgetMonthlySnapshotStatus.UNAVAILABLE -> WIDGET_STATUS_UNAVAILABLE
            }
        WidgetSnapshotOutcome.SurfaceAbsent -> WIDGET_STATUS_UNAVAILABLE
        WidgetSnapshotOutcome.RuntimeNotReady -> WIDGET_STATUS_OPEN_APP
    }

/**
 * The amount lines of one snapshot under [config]. EMPTY when the setting is hidden (the privacy
 * default) or the snapshot carries no amounts — an unavailable snapshot has nothing to format, so a
 * failure can never be rendered as `0.00`. The three lines are formatted from the exact minor units
 * with integer arithmetic only (no binary floating point).
 */
internal fun widgetAmountLines(
    snapshot: WidgetMonthlySnapshot?,
    config: LedgerWidgetConfig,
): List<String> {
    if (!config.showAmounts) return emptyList()
    val amounts = snapshot?.amounts ?: return emptyList()
    val precision = amounts.currency.precision
    return listOf(
        "$WIDGET_LABEL_INCOME ${formatWidgetMinorUnits(amounts.ordinaryIncomeMinorUnits, precision)} ${amounts.currency.code}",
        "$WIDGET_LABEL_NET_EXPENSE ${formatWidgetMinorUnits(amounts.netExpenseMinorUnits, precision)} ${amounts.currency.code}",
        "$WIDGET_LABEL_BALANCE ${formatWidgetMinorUnits(amounts.balanceMinorUnits, precision)} ${amounts.currency.code}",
    )
}

/**
 * Whether a result that was requested under [captured] must be DISCARDED because the widget's
 * current request no longer matches (spec section 6.4: "异步结果必须匹配 generation、月份及 widget
 * 当前配置 revision"). A mismatch on ANY of the three — runtime generation, target month or config
 * revision — discards the result, so a late visible result can never re-reveal an amount after the
 * setting was hidden, and a previous month's data can never be labelled as this month.
 */
internal fun shouldDiscardWidgetResult(
    captured: WidgetRenderRequest,
    current: WidgetRenderRequest,
): Boolean = captured != current

/**
 * The identity of ONE widget render request (spec section 6.4). A landing hop compares the captured
 * request against the widget's current request; any difference discards the result.
 */
internal data class WidgetRenderRequest(
    val widgetId: Int,
    val generation: Int,
    val month: YearMonth,
    val revision: Long,
)

/**
 * Integer-only minor-unit display formatting (the `formatMinorUnits` convention). No binary floating
 * point is ever involved; this is a display projection of the exact integer amount, never money
 * arithmetic. Kept local because app-ui's formatter is module-internal and the widget payload must
 * stay assertable on the plain JVM.
 */
private fun formatWidgetMinorUnits(
    minorUnits: Long,
    precision: Int,
): String {
    require(precision in 0..18) { "currency precision must be in 0..18" }
    val sign = if (minorUnits < 0L) "-" else ""
    val magnitude =
        when {
            minorUnits == Long.MIN_VALUE -> Long.MAX_VALUE
            minorUnits < 0L -> -minorUnits
            else -> minorUnits
        }
    val digits = magnitude.toString()
    if (precision == 0) return sign + digits
    val padded = digits.padStart(precision + 1, '0')
    return sign + padded.substring(0, padded.length - precision) + "." + padded.substring(padded.length - precision)
}

internal const val WIDGET_LINE_SEPARATOR: String = "\n"
internal const val WIDGET_STATUS_AVAILABLE: String = "本月收支"
internal const val WIDGET_STATUS_INVALID: String = "数据不一致，打开应用查看"
internal const val WIDGET_STATUS_UNAVAILABLE: String = "数据不可用，打开应用查看"
internal const val WIDGET_STATUS_OPEN_APP: String = "打开应用以查看本月收支"
internal const val WIDGET_UPDATED_PREFIX: String = "更新于 "

/**
 * P7-09 09.C (spec section 6.5): the frozen host-boundary disclosure. The desktop/lock-screen
 * presentation is controlled by the host, and hiding amounts cannot recall screenshots the host
 * already took. Amount-free, so it is shown in both the hidden and the visible state.
 */
internal const val WIDGET_HOST_NOTICE: String = "桌面与锁屏由系统控制；隐藏金额不能撤回已产生的截图"
internal const val WIDGET_LABEL_INCOME: String = "收入"
internal const val WIDGET_LABEL_NET_EXPENSE: String = "净支出"
internal const val WIDGET_LABEL_BALANCE: String = "结余"
