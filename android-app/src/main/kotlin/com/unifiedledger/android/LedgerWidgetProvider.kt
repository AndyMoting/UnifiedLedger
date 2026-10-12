package com.unifiedledger.android

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.MonthlyBuckets
import com.unifiedledger.ui.WidgetSnapshotOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.YearMonth
import kotlin.time.Clock

/*
 * P7-09 09.C monthly widget provider (D-225 Q20; spec section 6).
 *
 * THE ACCESS PATH (spec section 6.3). A widget update runs in a process with no composition, so it
 * MUST NOT reach the runtime through a composition-scoped owner and MUST NOT call the
 * bootstrap/migrate graph builder. It goes through the process-scoped holder
 * ([ledgerProcessHolderFor]) and the holder's lease-scoped accessor ([LedgerProcessHolder.ledger]),
 * which is null until the runtime has reached Ready. The provider deliberately does NOT start the
 * controller: starting it on a fresh install is exactly the "后台造空账本" the spec forbids, so a
 * widget on an unopened process renders the typed 不可用 state and guides the user to open the app.
 * A missing/corrupt ledger therefore shows 不可用, never a new empty database.
 *
 * THE MAIN-THREAD BUDGET (spec section 6.6). `onReceive` runs on the main thread with a ~10 second
 * budget, and `goAsync()` only EXTENDS that budget to `PendingResult.finish()` — it does not move
 * work off the main thread. The provider therefore posts the whole read onto a background
 * dispatcher and only comes back to the main dispatcher to write `RemoteViews`.
 *
 * THE PRIVACY CONSTRAINT (spec sections 6.1/6.5). The provider never formats an amount itself and
 * never puts one into an extra: every string it writes comes from [WidgetViewState], whose
 * hidden-state payload set is enforced by the pure functions in [LedgerWidgetSnapshotReader]. The
 * `PendingIntent`s carry a generic route token plus the widget id with explicit `FLAG_IMMUTABLE`
 * (spec section 6.1), so no `RemoteViews` text, `contentDescription`, `PendingIntent` extra, config
 * value or log line carries a plaintext amount.
 *
 * The receiver `exported`/`android:permission` values are NOT decided here: spec section 8 open item
 * 15 leaves the manifest security face to a per-entry ruling, so the manifest declares this receiver
 * non-exported and the values stay registered as an open item in the batch report.
 */
class LedgerWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // goAsync extends the broadcast budget; the work itself is posted to a background
        // dispatcher below (spec section 6.6), so the main thread is never blocked on a DB read.
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                for (widgetId in appWidgetIds) {
                    renderOne(context, appWidgetManager, widgetId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        // The per-widget visibility toggle (spec section 6.1: hidden by default, shown only after
        // the user turns it on). It is a broadcast to this provider — no new Activity, no config UI,
        // and its payload is the widget id plus this action only.
        if (intent.action == ACTION_TOGGLE_AMOUNTS) {
            val widgetId = intent.getIntExtra(WIDGET_ID_EXTRA, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                toggleAmounts(context, widgetId)
            }
            return
        }
        super.onReceive(context, intent)
    }

    private fun toggleAmounts(
        context: Context,
        widgetId: Int,
    ) {
        val store = LedgerWidgetConfigStore(context)
        val current = store.load(widgetId)
        // The revision ALWAYS advances, so any in-flight render computed under the previous setting
        // is discarded on landing and a late result can never re-reveal a hidden amount (spec
        // section 6.4; P709-A07).
        store.save(widgetId, current.withAmounts(!current.showAmounts))
        val manager = AppWidgetManager.getInstance(context)
        onUpdate(context, manager, intArrayOf(widgetId))
    }

    private suspend fun renderOne(
        context: Context,
        appWidgetManager: AppWidgetManager,
        widgetId: Int,
    ) {
        val config = LedgerWidgetConfigStore(context).load(widgetId)
        val month = currentReportingMonth()
        // The process-scoped holder; the accessor is null until the runtime is Ready. The
        // controller is deliberately NOT started here (spec section 6.3: no background empty
        // ledger, no silent migration).
        val ledger = ledgerProcessHolderFor(context)?.ledger
        val outcome = ledger?.widgetMonthlySnapshot(month) ?: WidgetSnapshotOutcome.RuntimeNotReady
        val captured =
            WidgetRenderRequest(
                widgetId = widgetId,
                generation = generationOf(outcome, ledger?.activeGenerationForLanding()),
                month = month,
                revision = config.revision,
            )
        // The landing hop runs on the main dispatcher: RemoteViews writes must not race, and the
        // discard decision is re-evaluated against the CURRENT widget state (spec section 6.4).
        withContext(Dispatchers.Main) {
            val currentConfig = LedgerWidgetConfigStore(context).load(widgetId)
            val current =
                WidgetRenderRequest(
                    widgetId = widgetId,
                    generation = ledger?.activeGenerationForLanding() ?: generationOf(outcome, null),
                    month = currentReportingMonth(),
                    revision = currentConfig.revision,
                )
            if (shouldDiscardWidgetResult(captured, current)) return@withContext
            val state = widgetViewState(month, outcome, currentConfig, current.generation, widgetId)
            appWidgetManager.updateAppWidget(widgetId, remoteViewsFor(context, state))
        }
    }

    /**
     * The captured generation of one read outcome: the snapshot's own generation when the read ran,
     * or the caller-supplied fallback (the active generation observed by the caller) otherwise.
     */
    private fun generationOf(
        outcome: WidgetSnapshotOutcome,
        fallback: Int?,
    ): Int = (outcome as? WidgetSnapshotOutcome.Read)?.snapshot?.generation ?: fallback ?: NO_GENERATION

    /**
     * Translates a [WidgetViewState] into `RemoteViews`. Every value written here comes from the
     * state, whose hidden-state payload set is amount-free when the setting is off — the provider
     * adds no string of its own, so no amount can leak past the pure decision layer.
     */
    private fun remoteViewsFor(
        context: Context,
        state: WidgetViewState,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.ledger_widget)
        val body = (listOf(state.month.toString(), state.statusText) + state.amountLines + listOfNotNull(state.updateText))
            .joinToString(WIDGET_LINE_SEPARATOR)
        views.setCharSequence(R.id.widget_body, "setText", body)
        // The accessibility text is the SAME amount-free value as the visible body (spec section
        // 6.5(b)): TalkBack reads nothing the screen does not show.
        views.setContentDescription(R.id.widget_body, state.contentDescription)
        // The click route opens the app on its current month through an existing entry; the
        // PendingIntent is explicitly immutable and carries only the generic route plus the widget
        // id (spec section 6.1).
        views.setOnClickPendingIntent(R.id.widget_body, clickPendingIntent(context, state, widgetIdExtra = false))
        views.setOnClickPendingIntent(R.id.widget_toggle, togglePendingIntent(context, state))
        views.setCharSequence(R.id.widget_toggle, "setText", toggleLabel(state))
        return views
    }

    private fun clickPendingIntent(
        context: Context,
        state: WidgetViewState,
        widgetIdExtra: Boolean,
    ): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .putExtra(WIDGET_CLICK_ROUTE_EXTRA, state.clickRoute.name)
        if (widgetIdExtra) intent.putExtra(WIDGET_ID_EXTRA, state.widgetId)
        // FLAG_IMMUTABLE is explicit (API 31+ requires an explicit mutability); no amount and no
        // ledger identifier travels in the extras (spec sections 6.1/6.5(c)).
        return PendingIntent.getActivity(
            context,
            state.widgetId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun togglePendingIntent(
        context: Context,
        state: WidgetViewState,
    ): PendingIntent {
        val intent =
            Intent(context, LedgerWidgetProvider::class.java)
                .setAction(ACTION_TOGGLE_AMOUNTS)
                .putExtra(WIDGET_ID_EXTRA, state.widgetId)
        return PendingIntent.getBroadcast(
            context,
            state.widgetId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun toggleLabel(state: WidgetViewState): String = if (state.amountLines.isEmpty()) WIDGET_TOGGLE_SHOW else WIDGET_TOGGLE_HIDE

    /** "This month" in the frozen reporting zone (spec section 6.4: the Shanghai natural month). */
    private fun currentReportingMonth(): YearMonth = MonthlyBuckets.currentMonth(LedgerClock { Clock.System.now() })

    companion object {
        /** The per-widget visibility-toggle broadcast action (never carries an amount). */
        internal const val ACTION_TOGGLE_AMOUNTS: String = "com.unifiedledger.android.widget.ACTION_TOGGLE_AMOUNTS"

        /** The generation sentinel for a read that never ran (never matches a live generation). */
        internal const val NO_GENERATION: Int = -1

        internal const val WIDGET_TOGGLE_SHOW: String = "显示金额"
        internal const val WIDGET_TOGGLE_HIDE: String = "隐藏金额"
    }
}
