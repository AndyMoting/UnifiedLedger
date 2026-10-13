package com.unifiedledger.android

import android.content.Context

/*
 * P7-09 09.C per-widget display configuration (D-225 Q20; spec sections 6.1/6.5, open item 4).
 *
 * Spec section 6.5 freezes two things about this state: (a) each widget's display setting is
 * INDEPENDENT and the initial value is HIDDEN (an amount is shown only after the user turns it on),
 * and (b) it is PLATFORM LOCAL STORAGE — it never enters the ledger schema and is excluded from the
 * P7-06 product backup. It also hard-forbids storing a plaintext amount anywhere in this state.
 *
 * The VALUE TYPE is pure and JVM-testable; the Android binding is a thin SharedPreferences read/write
 * that stores exactly one boolean and one revision counter as text. No amount, no ledger id, no
 * account name and no month ever reaches it, so the hidden-state payload set (spec section 6.5(a)-(e))
 * holds by construction rather than by discipline. The exact storage mechanism (key shape, whether
 * the boolean is encrypted) remains spec section 8 open item 4 — registered, not decided here.
 */

/**
 * The per-widget display state (spec section 6.1/6.5).
 *
 * [revision] is the monotonic CONFIG REVISION the async landing must match (spec section 6.4: "异步
 * 结果必须匹配 generation、月份及 widget 当前配置 revision"): toggling the setting advances it, so a
 * result computed under the old setting is discarded instead of re-revealing a previously hidden
 * amount (P709-A07).
 */
internal data class LedgerWidgetConfig(
    val showAmounts: Boolean,
    val revision: Long,
) {
    companion object {
        /** The frozen default: amounts are HIDDEN until the user explicitly turns them on. */
        fun hidden(): LedgerWidgetConfig = LedgerWidgetConfig(showAmounts = false, revision = 0L)
    }

    /**
     * The same widget with the visibility setting toggled to [visible]; the revision always
     * advances, so any in-flight render computed under the old setting is discarded on landing
     * (spec section 6.4). Turning the setting OFF is the privacy-critical direction: the revision
     * bump is what stops a late visible result from re-revealing an amount after the user hid it.
     */
    fun withAmounts(visible: Boolean): LedgerWidgetConfig = LedgerWidgetConfig(showAmounts = visible, revision = revision + 1)
}

/** The stable per-widget storage key (spec section 6.5: widget-local, device-local, backup-excluded). */
internal fun widgetConfigStorageKey(widgetId: Int): String = "widget_config_$widgetId"

/** The boolean preference key holding the per-widget visibility setting. */
internal const val WIDGET_CONFIG_SHOW_AMOUNTS_KEY: String = "show_amounts"

/** The long preference key holding the per-widget config revision. */
internal const val WIDGET_CONFIG_REVISION_KEY: String = "config_revision"

/**
 * The Android binding for [LedgerWidgetConfig]: a per-widget SharedPreferences read/write that
 * stores ONLY the visibility boolean and the revision counter. It never stores an amount, a ledger
 * id, an account name or a month, so the spec section 6.5 hidden-state hard constraint cannot be
 * violated through this state.
 */
internal class LedgerWidgetConfigStore(
    private val context: Context,
) {
    fun load(widgetId: Int): LedgerWidgetConfig {
        val prefs = context.getSharedPreferences(widgetConfigStorageKey(widgetId), Context.MODE_PRIVATE)
        return LedgerWidgetConfig(
            showAmounts = prefs.getBoolean(WIDGET_CONFIG_SHOW_AMOUNTS_KEY, false),
            revision = prefs.getLong(WIDGET_CONFIG_REVISION_KEY, 0L),
        )
    }

    fun save(
        widgetId: Int,
        config: LedgerWidgetConfig,
    ) {
        context
            .getSharedPreferences(widgetConfigStorageKey(widgetId), Context.MODE_PRIVATE)
            .edit()
            .putBoolean(WIDGET_CONFIG_SHOW_AMOUNTS_KEY, config.showAmounts)
            .putLong(WIDGET_CONFIG_REVISION_KEY, config.revision)
            .apply()
    }

    /**
     * Clears the per-widget state when the widget is removed (spec section 6.5: the setting is
     * per-widget and defaults to hidden). Removing a widget must not leave its "show amounts" flag
     * behind, or a later widget the host assigns the same id would inherit a visible state.
     */
    fun clear(widgetId: Int) {
        context
            .getSharedPreferences(widgetConfigStorageKey(widgetId), Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
