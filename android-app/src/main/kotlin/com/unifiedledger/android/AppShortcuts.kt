package com.unifiedledger.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.os.PersistableBundle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/*
 * P7-09 09.B (D-226; spec sections 5/5.1): the launcher shortcut face — 记支出 / 记收入 / 导入.
 *
 * MECHANISM (spec section 8 open item 3 "动态 vs 静态/trampoline 最终选择" is still OPEN for the main
 * session; this batch's implementation choice, registered in the batch report, is DYNAMIC):
 * `ShortcutManager` dynamic shortcuts are used and NO static `res/xml/shortcuts.xml` is added.
 * Evidence gathered this batch, all from the local API surface
 * (the local Android SDK: `javap` over the android-37.0 `android.jar`, plus the android-36.1
 * platform sources `android/content/pm/ShortcutInfo.java`, `android/content/pm/ShortcutManager.java`):
 * - `ShortcutInfo.enforceMandatoryFields(forPinned = false)` requires ID, activity, short label and
 *   a non-empty intent array; an ICON is NOT required, so this batch adds no icon drawable;
 * - `ShortcutInfo.Builder.setIntents` requires every intent to carry an ACTION
 *   (`Objects.requireNonNull(intent.getAction())`), so each shortcut intent sets one;
 * - `setDynamicShortcuts`/`addDynamicShortcuts` are `@WorkerThread` and rate-limited but require NO
 *   foreground activity (only `requestPinShortcut` does), so publishing from the composition is
 *   legal;
 * - a dynamic shortcut's intent is a plain `startActivity` intent, so the static shortcut's default
 *   `NEW_TASK`+`CLEAR_TASK` (spec section 5.1: it would destroy the app's existing task and could
 *   lose a draft / interrupt a commit) does NOT apply here and is deliberately not reproduced.
 *
 * TASK DELIVERY (spec section 5.1; official `Intent` reference, quoted one line each in the spec):
 * each intent names [MainActivity] EXPLICITLY and carries [Intent.FLAG_ACTIVITY_NEW_TASK] only.
 * `MainActivity` is the app's task ROOT and `launchMode="singleTop"` (AndroidManifest.xml), i.e. both
 * conditions under which the official reference delivers an existing task's intent through
 * `onNewIntent`; `MainActivity.onNewIntent` calls `setIntent()` and re-runs the SAME intake. A cold
 * delivery (no live task) arrives through `onCreate`. Either way the request goes through the SAME
 * `ExternalNavigationChannel` and the SAME navigation gate as a share, so a shortcut can never
 * bypass the startup door or the confirmation chain. `CLEAR_TASK`/`CLEAR_TOP` are deliberately NOT
 * set: both may destroy an in-progress draft or submit.
 *
 * PRIVACY (spec section 5; J1): the labels are the three GENERIC route names and carry NO account
 * name, NO category name and NO amount — the launcher can read shortcut metadata. The intents carry
 * the routing action ONLY: no extras at all, so nothing here can become a formal submission
 * snapshot (spec section 5: shortcut extras express routing only).
 *
 * The device leg — real launcher task-stack behaviour, an app upgrade, a pinned/static variant and
 * TalkBack (spec sections 5.1/8 items 3/6/7, P709-A04) — is 09.D's, NOT claimed here.
 */

/**
 * P7-09 09.B (D-226): one shortcut's PURE description — the platform-free projection of what gets
 * published, so the ID set, the labels and the route are JVM-assertable without an `Intent` or a
 * `ShortcutManager` (constructing either needs the Android framework, or a non-inlined stub).
 *
 * [id] is a stable generic identifier (never a per-user value). [route] is the explicit
 * [ShortcutNavigationAction] the shortcut delivers; it is the ONLY routing information published.
 */
internal data class LedgerShortcutSpec(
    /** Stable generic ID; stable across versions so a re-publish updates instead of duplicating. */
    val id: String,
    /** The launcher-visible short label: a generic route name, never an account/category/amount. */
    val shortLabel: String,
    /** The launcher-visible long label: same constraint as [shortLabel]. */
    val longLabel: String,
    /** The explicit action the shortcut Intent carries (see [ShortcutNavigationAction]). */
    val route: String,
    /** Display order on the launcher surface (lower first). */
    val rank: Int,
)

/**
 * P7-09 09.B (D-226; spec section 5): the three published shortcuts. Ordered 记支出 / 记收入 / 导入
 * (the plan section 9.2 order). Every label is a generic route name; none may ever carry an account
 * name, a category name or an amount.
 */
internal val LEDGER_SHORTCUT_SPECS: List<LedgerShortcutSpec> =
    listOf(
        LedgerShortcutSpec(
            id = "com.unifiedledger.android.shortcut.NEW_EXPENSE",
            shortLabel = "记支出",
            longLabel = "记一笔支出",
            route = ShortcutNavigationAction.NEW_EXPENSE,
            rank = 0,
        ),
        LedgerShortcutSpec(
            id = "com.unifiedledger.android.shortcut.NEW_INCOME",
            shortLabel = "记收入",
            longLabel = "记一笔收入",
            route = ShortcutNavigationAction.NEW_INCOME,
            rank = 1,
        ),
        LedgerShortcutSpec(
            id = "com.unifiedledger.android.shortcut.OPEN_IMPORT",
            shortLabel = "导入",
            longLabel = "打开导入",
            route = ShortcutNavigationAction.OPEN_IMPORT,
            rank = 2,
        ),
    )

/**
 * P7-09 09.B (D-226; spec section 8 item 6): caps [specs] at the platform's per-activity limit. The
 * concrete `getMaxShortcutCountPerActivity()` value is device/API evidence (OPEN, spec section 8
 * item 6) and is NOT assumed here — the caller passes what the platform reported, and exceeding it
 * would make `setDynamicShortcuts` throw `IllegalArgumentException`
 * (`android/content/pm/ShortcutManager.java`). Pure; the JVM test drives it with a synthetic limit.
 */
internal fun ledgerShortcutSpecsWithinLimit(
    specs: List<LedgerShortcutSpec>,
    maxShortcutCountPerActivity: Int,
): List<LedgerShortcutSpec> = if (maxShortcutCountPerActivity <= 0) emptyList() else specs.take(maxShortcutCountPerActivity)

/**
 * P7-09 09.B (D-226; spec section 8 item 6): the once-per-process publish gate. Pure in its control
 * flow (the platform work is injected), so the gate, the cap and the background hop are
 * JVM-assertable at the real decision point rather than only on their parts:
 * - a later caller (a re-composition, an Activity recreation, a second entry) finds the gate already
 *   claimed and returns WITHOUT scheduling anything — this is what keeps ordinary use off the
 *   platform's publish rate limit;
 * - the first caller claims the gate and runs [planFor]/[publish] on [scope] (the caller's background
 *   hop, because `addDynamicShortcuts` is `@WorkerThread`);
 * - [planFor] returning `null` (an absent `ShortcutManager`) or an empty list (the platform reported
 *   no usable capacity — publishing anyway would make `addDynamicShortcuts` throw
 *   `IllegalArgumentException`, `android/content/pm/ShortcutManager.java`) publishes nothing.
 *
 * [scope] is supplied by the caller so a test drives it with a direct dispatcher.
 */
internal fun publishShortcutsOnce(
    gate: AtomicBoolean,
    scope: CoroutineScope,
    planFor: () -> List<LedgerShortcutSpec>?,
    publish: (List<LedgerShortcutSpec>) -> Unit,
) {
    if (!gate.compareAndSet(false, true)) return
    scope.launch {
        runCatching {
            val plan = planFor() ?: return@runCatching
            if (plan.isEmpty()) return@runCatching
            publish(plan)
        }
    }
}

/**
 * P7-09 09.B (D-226; spec sections 5/5.1): the Android shortcut publisher. The composition root
 * calls [publishOnce] once per process; a re-composition, an Activity recreation or a second entry
 * never re-publishes (the `ensureStartedOnce` single-flight precedent, reused rather than
 * duplicated), so the platform's publish rate limit is never approached by ordinary use.
 *
 * Publication drives the app's own [UnifiedLedgerApplication]/[LedgerProcessHolder] process; it never
 * touches the ledger, never opens a graph and carries no ledger data.
 */
internal object AppShortcuts {
    private val publishedOnce = AtomicBoolean(false)

    /**
     * The dispatcher the platform publish hops to. `ShortcutManager.addDynamicShortcuts` is annotated
     * `@WorkerThread` (`android/content/pm/ShortcutManager.java`), so calling it from `onCreate` (the
     * main thread) would violate that contract; the publish therefore runs on `Dispatchers.IO`, the
     * same background class the process controller's blocking open uses. Publication is fire-and-
     * forget (it only describes generic routes and touches no ledger data), so a caller never waits
     * on it.
     */
    private val publishDispatcher: CoroutineDispatcher = Dispatchers.IO

    /**
     * Publishes the dynamic shortcuts at most once per process; every later call is a no-op. The
     * once-per-process gate, the platform-limit cap and the background hop are decided by the pure
     * [publishShortcutsOnce] / [shortcutPublishPlan] seams (the `ensureStartedOnce` extraction
     * precedent), so the wiring is JVM-assertable without a `Context`.
     *
     * The `@WorkerThread` platform calls run on [publishDispatcher], off the caller's thread;
     * `getMaxShortcutCountPerActivity()` is read there too. An absent `ShortcutManager` (a plain
     * unit-test context) publishes nothing.
     */
    fun publishOnce(context: Context) {
        val applicationContext = context.applicationContext ?: context
        publishShortcutsOnce(
            gate = publishedOnce,
            scope = CoroutineScope(SupervisorJob() + publishDispatcher),
            planFor = {
                val manager =
                    applicationContext.getSystemService(ShortcutManager::class.java)
                        ?: return@publishShortcutsOnce null
                ledgerShortcutSpecsWithinLimit(LEDGER_SHORTCUT_SPECS, manager.maxShortcutCountPerActivity)
            },
            publish = { plan -> publish(applicationContext, plan) },
        )
    }

    /**
     * Publishes one already-decided [plan] on the CURRENT (already background) thread. Idempotent by
     * ID (`addDynamicShortcuts` updates an existing shortcut with the same ID), so a repeat cannot
     * duplicate a launcher entry. Empty plan → nothing published. `@WorkerThread` contract: the
     * caller is [publishOnce]'s background hop.
     */
    fun publish(
        context: Context,
        plan: List<LedgerShortcutSpec>,
    ) {
        if (plan.isEmpty()) return
        runCatching {
            val manager = context.getSystemService(ShortcutManager::class.java) ?: return
            val infos = plan.map { spec -> shortcutInfo(context, spec) }
            manager.addDynamicShortcuts(infos)
            StartupTrace.emit("shortcuts.published count=${infos.size}")
        }
    }

    /**
     * The `ShortcutInfo` for one spec: explicit target [MainActivity], the spec's routing action and
     * NO extras (the diagnostic-free, leak-free minimum). Not JVM-assertable (it builds platform
     * objects), which is why [LEDGER_SHORTCUT_SPECS] carries the assertable facts.
     */
    private fun shortcutInfo(
        context: Context,
        spec: LedgerShortcutSpec,
    ): ShortcutInfo =
        ShortcutInfo
            .Builder(context, spec.id)
            .setActivity(ComponentName(context, MainActivity::class.java))
            .setShortLabel(spec.shortLabel)
            .setLongLabel(spec.longLabel)
            .setRank(spec.rank)
            .setExtras(PersistableBundle())
            .setIntent(shortcutIntent(context, spec))
            .build()

    /**
     * The shortcut's launch Intent: explicit component, the spec's routing action, `NEW_TASK` only
     * (see the file KDoc for why `CLEAR_TASK`/`CLEAR_TOP` are deliberately absent).
     */
    private fun shortcutIntent(
        context: Context,
        spec: LedgerShortcutSpec,
    ): Intent =
        Intent(spec.route).apply {
            setClass(context, MainActivity::class.java)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
