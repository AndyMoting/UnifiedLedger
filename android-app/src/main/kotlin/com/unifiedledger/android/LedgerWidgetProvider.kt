package com.unifiedledger.android

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.MonthlyBuckets
import com.unifiedledger.ui.ExternalNavigationRequest
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
 * click `PendingIntent` carries the generic route token ONLY and the toggle/refresh `PendingIntent`s
 * carry the host-assigned widget id ONLY, all with explicit `FLAG_IMMUTABLE` (spec section 6.1), so
 * no `RemoteViews` text, `contentDescription`, `PendingIntent` extra, config value or log line
 * carries a plaintext amount.
 *
 * THE REFRESH LEGS DELIVERED HERE (spec section 6.4). All THREE frozen legs are wired: the
 * platform period (`updatePeriodMillis` = 30 minutes in `ledger_widget_info.xml`), the
 * user-initiated refresh ([ACTION_REFRESH]), and the FIRST leg — "已知正式写入成功/恢复成功的合并失效"
 * (invalidating every widget after a confirmed write/restore). The first leg is bridged from the
 * composition: `P503App` reports each determinate landing through the platform-neutral
 * [com.unifiedledger.ui.WidgetInvalidationObserver], the Android root hands it
 * [LedgerProcessHolder.invalidateAllWidgets] (this class's [reRenderAllWidgets]), and that fans the
 * SAME guarded render path out to every host-assigned widget id — so a confirmed write is picked up
 * immediately instead of at the next platform period or manual refresh. The widget still always
 * shows its `asOf` time, so the data's age is visible rather than implied live.
 *
 * The receiver `exported`/`android:permission` values are NOT decided here: spec section 8 open item
 * 15 leaves the manifest security face to a per-entry ruling (main-session/device evidence). This
 * batch declares the receiver non-exported as the conservative choice and registers that unresolved
 * ruling in the batch report.
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
        renderAll(context, appWidgetManager, appWidgetIds) { pendingResult.finish() }
    }

    /**
     * Posts the per-widget render loop onto a background dispatcher and runs [onDone] on
     * completion. The SAME loop serves the platform/user-initiated broadcast legs ([onUpdate],
     * which passes `pendingResult.finish()`) and the composition-initiated invalidation
     * ([reRenderAllWidgets], which has no broadcast and therefore no `goAsync` budget); the
     * generation/month/revision landing discard inside [renderOne] applies identically to every
     * caller (spec section 6.4).
     */
    private fun renderAll(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        onDone: () -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                for (widgetId in appWidgetIds) {
                    renderOne(context, appWidgetManager, widgetId)
                }
            } finally {
                onDone()
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
        // P7-09 09.C (spec section 6.4): the user-initiated refresh leg. It re-runs the SAME
        // guarded render path a platform period runs, so the generation/month/revision landing check
        // applies identically (a stale read is still discarded).
        if (intent.action == ACTION_REFRESH) {
            val widgetId = intent.getIntExtra(WIDGET_ID_EXTRA, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                onUpdate(context, AppWidgetManager.getInstance(context), intArrayOf(widgetId))
            }
            return
        }
        if (intent.action == ACTION_OPEN_CURRENT_MONTH) {
            // P7-09 09.C (spec sections 6.1/6.5; J5): the click route genuinely REUSES the existing
            // neutral request family — the process-scoped holder's channel delivers
            // `OpenCurrentMonth` (ExternalNavigation.kt:34-35, routed at P503Reducer.kt:406) to the
            // composition's routing gate, so a widget click lands on the app's current-month surface
            // through the SAME navigation door every other entry uses. This adds NO fourth neutral
            // request type (the A6 frozen face), and the extras carry the generic action only.
            val holder = ledgerProcessHolderFor(context)
            holder?.externalNavigationChannel?.deliver(ExternalNavigationRequest.OpenCurrentMonth)
            launchAppActivity(context)
            return
        }
        super.onReceive(context, intent)
    }

    /**
     * P7-09 09.C (spec section 6.5: each widget's display setting is INDEPENDENT and defaults to
     * hidden). When a widget is removed its per-widget preference is cleared with it, so a later
     * widget that the host happens to assign the same id starts from the frozen hidden default
     * instead of inheriting a previous widget's "show amounts" state.
     */
    override fun onDeleted(
        context: Context,
        appWidgetIds: IntArray,
    ) {
        for (widgetId in appWidgetIds) {
            LedgerWidgetConfigStore(context).clear(widgetId)
        }
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
        val body =
            (listOf(state.month.toString(), state.statusText) + state.amountLines + listOfNotNull(state.updateText, state.hostNoticeText))
                .joinToString(WIDGET_LINE_SEPARATOR)
        views.setCharSequence(R.id.widget_body, "setText", body)
        // The accessibility text is the SAME amount-free value as the visible body (spec section
        // 6.5(b)): TalkBack reads nothing the screen does not show.
        views.setContentDescription(R.id.widget_body, state.contentDescription)
        // The click route opens the app on its current month; the PendingIntent is explicitly
        // immutable and carries ONLY the generic route (the widget id rides on the toggle broadcast,
        // never on the click intent) — spec section 6.1.
        views.setOnClickPendingIntent(R.id.widget_body, clickPendingIntent(context, state))
        views.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent(context, state))
        views.setCharSequence(R.id.widget_refresh, "setText", WIDGET_REFRESH_LABEL)
        views.setOnClickPendingIntent(R.id.widget_toggle, togglePendingIntent(context, state))
        views.setCharSequence(R.id.widget_toggle, "setText", toggleLabel(state))
        return views
    }

    /**
     * The click route (spec sections 6.1/6.5). Instead of a direct Activity PendingIntent, the
     * widget body fires THIS provider's generic open action; the receiver then delivers the existing
     * neutral request `OpenCurrentMonth` to the process-scoped channel (so the app routes it through
     * its normal navigation gate) and brings the app forward. The PendingIntent is explicitly
     * immutable and its extras carry the generic action only — never an amount, a widget id or a
     * ledger identifier.
     */
    private fun clickPendingIntent(
        context: Context,
        state: WidgetViewState,
    ): PendingIntent {
        val intent =
            Intent(context, LedgerWidgetProvider::class.java)
                .setAction(ACTION_OPEN_CURRENT_MONTH)
        return PendingIntent.getBroadcast(
            context,
            state.widgetId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * Brings the app's single Activity forward (spec section 6.1: the click opens the app). Launched
     * from a receiver, so `FLAG_ACTIVITY_NEW_TASK` is required; the neutral
     * `OpenCurrentMonth` request delivered alongside selects the current month, so the flags carry no
     * routing data themselves.
     */
    private fun launchAppActivity(context: Context) {
        val intent =
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // A launcher-less/disabled Activity would throw; a failed "bring to front" must not crash the
        // receiver (the broadcast still completes). Diagnostics only — no amount, no ledger id.
        runCatching { context.startActivity(intent) }
    }

    /**
     * P7-09 09.C (spec section 6.4: user-initiated refresh is one of the three refresh legs, beside
     * the platform period and the on-write/on-restore invalidation). The control fires this
     * provider's own refresh action, which re-reads the current month exactly like a platform period
     * would — it is the same generation/month/revision-guarded path, never a second read mechanism.
     */
    private fun refreshPendingIntent(
        context: Context,
        state: WidgetViewState,
    ): PendingIntent {
        val intent =
            Intent(context, LedgerWidgetProvider::class.java)
                .setAction(ACTION_REFRESH)
                .putExtra(WIDGET_ID_EXTRA, state.widgetId)
        return PendingIntent.getBroadcast(
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

        /** P7-09 09.C (spec section 6.4): the user-initiated refresh broadcast action. */
        internal const val ACTION_REFRESH: String = "com.unifiedledger.android.widget.ACTION_REFRESH"

        /**
         * P7-09 09.C (spec sections 6.1/6.5; J5): the widget click action. The receiver maps it to
         * the EXISTING neutral request `OpenCurrentMonth` — no new request type, no data extras.
         */
        internal const val ACTION_OPEN_CURRENT_MONTH: String = "com.unifiedledger.android.widget.ACTION_OPEN_CURRENT_MONTH"

        /** The generation sentinel for a read that never ran (never matches a live generation). */
        internal const val NO_GENERATION: Int = -1

        internal const val WIDGET_TOGGLE_SHOW: String = "显示金额"
        internal const val WIDGET_TOGGLE_HIDE: String = "隐藏金额"

        /** The user-initiated refresh control label (spec section 6.4); never an amount. */
        internal const val WIDGET_REFRESH_LABEL: String = "刷新"

        /**
         * P7-09 09.C (spec section 6.4; D-225 Q20): re-render EVERY host-assigned widget instance.
         *
         * This is the receiver half of the first refresh leg — "已知正式写入成功/恢复成功的合并失效".
         * The Android root binds it (through [LedgerProcessHolder.invalidateAllWidgets]) to the
         * platform-neutral `WidgetInvalidationObserver` the composition reports determinate
         * successes through, so a confirmed write re-renders every widget immediately.
         *
         * It reaches the provider by COMPONENT (never an Activity launch), enumerates the live
         * widget ids from the platform, and re-runs the SAME guarded render loop a broadcast update
         * runs — so a stale result is still discarded on landing (spec section 6.4) and NO full-ledger
         * scan is started. An app with no placed widget enumerates zero ids and does no work.
         */
        fun reRenderAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = activeWidgetIds(manager.getAppWidgetIds(ComponentName(context, LedgerWidgetProvider::class.java)))
            if (ids.isEmpty()) return
            LedgerWidgetProvider().renderAll(context, manager, ids, onDone = {})
        }

        /**
         * The JVM-assertable seam of the fan-out: the ids that actually need a re-render. A null
         * array (the platform never returns one, but a defensive read can) and an empty array both
         * mean "no widget placed", and the copy keeps the caller's array out of the render loop.
         */
        internal fun activeWidgetIds(ids: IntArray?): IntArray = ids?.copyOf() ?: IntArray(0)
    }
}
