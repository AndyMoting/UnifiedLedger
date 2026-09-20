package com.unifiedledger.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import kotlin.math.abs

private const val TAG = "UlScaleTraversal"
private const val EVIDENCE_FILE_NAME = "ul-scale-traversal.log"
private const val TARGET_PACKAGE = "com.unifiedledger.android"
private const val LAUNCH_COMMAND = "am start -n com.unifiedledger.android/.MainActivity"
private const val MAIN_ACTIVITY_CLASS = "$TARGET_PACKAGE.MainActivity"
private const val IMPORT_TAB_LABEL = "导入"
private const val IMPORT_TAB_FALLBACK_X = 736
private const val IMPORT_TAB_FALLBACK_Y = 2242

// The import tab is bottom-band candidate index 3 (the 4th tab): the tab-bar geometry measured on the
// managed AVD's 1080x2400 profile puts the four tab nodes in the band below (each 183-184 px wide at
// y=2095..2305, centres x=124/328/532/736, the import one selected at [645,2095][828,2305]). The label
// lookup that used to resolve the tab can never succeed here - the tab-bar nodes expose no text and no
// contentDescription, only the 65 px wide label TextView child carries 导入 - and the old
// nearest-clickable coordinate fallback picked the 3rd tab (x=532), which switched the app away from
// the import tab. The same band and width window also hold each tab's inner decorations (126-127 px
// boxes) and the 147 px wide new-expense FAB, so the raw candidates are collapsed to one node per tab
// position before the index is read.
private const val TAB_BAND_TOP = 2_000
private const val TAB_BAND_BOTTOM = 2_400
private const val TAB_WIDTH_MIN = 120
private const val TAB_WIDTH_MAX = 260
private const val IMPORT_TAB_INDEX = 3
private const val TAB_MAX_ATTEMPTS = 4
private const val TAB_LIST_WAIT_MILLIS = 30_000
private const val CANDIDATE_CHECKBOX_DESC = "勾选候选"
private const val CURRENCY_SUFFIX = "CNY"
private const val LAST_ITEM_ROW_INDEX = 1_000_000
private const val ARG_TARGET = "target"
private const val ARG_MAX_FORWARD_ACTIONS = "maxForwardActions"
private const val ARG_SETTLE_MILLIS = "settleMillis"
private const val ARG_N = "n"
private const val DEFAULT_TARGET = "整组标记为重复"
private const val DEFAULT_MAX_FORWARD_ACTIONS = 6000
private const val DEFAULT_SETTLE_MILLIS = 120
private const val DEFAULT_FORWARD_PROBE_ACTIONS = 200
private const val FORWARD_PROBE_SETTLE_MILLIS = 120
private const val FORWARD_PROBE_LOG_EVERY = 25
private const val REFETCH_EVERY = 50
private const val PROGRESS_EVERY = 250
private const val CONSECUTIVE_FAILURE_LIMIT = 200
private const val APP_ROOT_WAIT_MILLIS = 20_000
private const val POST_LAUNCH_ROOT_WAIT_MILLIS = 30_000
private const val LIST_WAIT_MILLIS = 60_000
private const val CLICK_WAIT_MILLIS = 30_000
private const val POLL_MILLIS = 500
private const val CONTAINER_REFETCH_RETRIES = 10
private const val CONTAINER_REFETCH_RETRY_MILLIS = 500
private const val LOGCAT_CHUNK_CHARS = 900
private const val KEY_TEXT_LIMIT = 40
private const val DIAGNOSE_PREFIX = "diagnose:"
private const val DIAGNOSE_SETTLE_MILLIS = 3_000
private const val DIAGNOSE_POLL_TOTAL_MILLIS = 30_000
private const val DIAGNOSE_POLL_INTERVAL_MILLIS = 500
private const val DIAGNOSE_POLL_LOG_FIRST = 5
private const val DIAGNOSE_POLL_LOG_PERIOD_MILLIS = 5_000

// ---- groupDisposition (D-161 follow-up: the whole remaining section 10.3 flow in one run) ----

private const val ARG_STALL_CHECKS = "stallChecks"
private const val ARG_CONFIRM_TIMEOUT_MILLIS = "confirmTimeoutMillis"
private const val ARG_WAIT_FOR_INTAKE_MILLIS = "waitForIntakeMillis"
private const val ARG_CARD_SCROLL_ACTIONS = "cardScrollActions"
private const val DEFAULT_STALL_CHECKS = 20
private const val DEFAULT_GROUP_MAX_FORWARD_ACTIONS = 30_000
private const val DEFAULT_GROUP_SETTLE_MILLIS = 120
private const val DEFAULT_CONFIRM_TIMEOUT_MILLIS = 3_600_000
private const val DEFAULT_WAIT_FOR_INTAKE_MILLIS = 0
private const val DEFAULT_CARD_SCROLL_ACTIONS = 2_000
private const val INTAKE_RENDER_MILLIS = 8_000
private const val INTAKE_POLL_MILLIS = 5_000
private const val INTAKE_STABLE_POLLS = 3
private const val CARD_SCROLL_PROGRESS_EVERY = 100
private const val GROUP_REFETCH_EVERY = 25
private const val GROUP_PROGRESS_EVERY = 500
private const val GROUP_DISPOSITION_PREFIX = "groupDisposition:"
private const val GROUP_CARD_TITLE = "整组标记为重复（逐条核对）"
private const val GROUP_CARD_CONFIRM_LABEL = "确认整组标记"
private const val GROUP_ITEM_COUNT_PREFIX = "共 "
private const val GROUP_ITEM_COUNT_SUFFIX = " 条。"
private const val OUTCOME_PENDING_TEXT = "待处置"
private const val OUTCOME_MARKED_PREFIX = "已标记"
private const val OUTCOME_FAILED_PREFIX = "失败"
private const val CONFIRMED_DUPLICATE_STATUS = "CONFIRMED_DUPLICATE"
private const val GROUP_CARD_WAIT_MILLIS = 120_000
private const val GROUP_POLL_MILLIS = 2_000
private const val GROUP_PROGRESS_LOG_MILLIS = 30_000
private const val GROUP_STABLE_CHECKS = 5
private const val GROUP_FALLBACK_TIMEOUT_DIVISOR = 2
private const val CLICK_PARENT_DEPTH = 4
private const val DB_FILE_NAME = "ledger.db"

/**
 * The duplicate-status histogram SQL. `import_duplicate_status_history` is append-only with
 * `PRIMARY KEY (ledger_id, candidate_id, sequence)`, so the row with the greatest `sequence` per
 * pair carries the current status (the `import_duplicate_history_terminal` trigger makes the first
 * non-`DEFERRED` row terminal). Column names are taken from the shipped schema
 * (`ledger-data/src/commonMain/sqldelight/com/unifiedledger/data/db/Ledger.sq`, created verbatim by
 * `23.sqm`): `ledger_id`, `candidate_id`, `sequence`, `history_id`, `status`, `request_id`,
 * `operation_class`. The resolved SQL is logged by every read so the host sees exactly what ran.
 */
private const val DUPLICATE_STATUS_HISTOGRAM_SQL =
    "SELECT h.status AS status, COUNT(*) AS total FROM import_duplicate_status_history h " +
        "WHERE h.sequence = (SELECT MAX(x.sequence) FROM import_duplicate_status_history x " +
        "WHERE x.ledger_id = h.ledger_id AND x.candidate_id = h.candidate_id) " +
        "GROUP BY h.status ORDER BY h.status"

/**
 * D-161 (docs/DECISIONS.md): the section 10.3 large-library traversal test infrastructure - a
 * programmatic scroll actuator for the import candidate list. D-159 section 3 measured that
 * mechanical fling traversal of the 50k-candidate library stops advancing after roughly 50 flings
 * (OBS-APERF-INPUT-FREEZE), and that the substitute input channels (keyboard page-down,
 * MOVE_END/DPAD, trackball) do not advance the list at that scale, so the traversal the plan
 * requires is blocked by the automation input mode rather than by the list itself. This class
 * reaches the product entries at the end of the candidate list (group disposition and batch
 * confirmation) without ever synthesizing a pointer gesture: it reads the platform accessibility
 * tree through `UiAutomation` and invokes the Compose lazy list's scroll semantics actions
 * (`ACTION_SCROLL_TO_POSITION` first, `ACTION_SCROLL_FORWARD` as the fallback), so no
 * `input swipe` / `input tap` gesture storm - the INPUT-FREEZE trigger - is produced.
 *
 * Root retrieval is deliberately redundant. The first device run of this class failed at its
 * precondition ("MainActivity window did not become reachable within 60000 ms") while the activity
 * was displayed and the host-side `uiautomator dump` returned the app tree: the instrumentation's
 * own `UiAutomation` reported `flags=0`, so `rootInActiveWindow` alone did not expose the app
 * window. Every mode therefore calls [prepareAutomation] once to set
 * `AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS` before touching the tree, and every
 * tree read goes through [appRoot], which prefers `rootInActiveWindow` when it belongs to the app
 * package and otherwise scans `UiAutomation.windows` for an app window, preferring the active or
 * focused one. Precondition failures carry the observed window state in their assertion message.
 *
 * Test-only infrastructure (D-161): zero product code, zero new dependencies (the instrumentation
 * source set already carries `androidx.test:runner` and `androidx.test.ext:junit`), no Compose UI
 * test or Espresso artifacts, and no product or schema change. CI keeps zero connectedAndroidTest;
 * run it manually on the managed emulator (isolated adb) as gate evidence, for example:
 *
 *   am instrument -w -e class com.unifiedledger.android.ImportScaleTraversalInstrumentedTest#probe
 *   com.unifiedledger.android.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Every mode reads its own instrumentation arguments from `InstrumentationRegistry.getArguments()`
 * and appends its evidence to both logcat (tag [TAG]) and the app's files dir, at
 * `files/ul-scale-traversal.log` (read on the host with
 * `run-as com.unifiedledger.android cat files/ul-scale-traversal.log`). Modes:
 * - `probe`: diagnostics only. Brings the app to the import review screen, logs every scrollable
 *   node found, and for the chosen one its action list, collection info, scrollable flag and the
 *   first/last visible candidate row texts. Taps nothing else and finishes no activity.
 * - `scrollToEnd`: the traversal. Args `target` (default 整组标记为重复), `maxForwardActions`
 *   (default 6000), `settleMillis` (default 120), `stallChecks` (default 20). Jumps with
 *   `ACTION_SCROLL_TO_POSITION` at a very large row index, falls back to repeated
 *   `ACTION_SCROLL_FORWARD`, then clicks the target node and logs the key texts that follow.
 * - `scrollForwardProbe`: bounded pure-fallback diagnostic. Arg `n` (default 200) forward actions
 *   with a rows-per-action reading every 25 actions; taps nothing.
 * - `diagnose`: fast assertion-free probe of root retrieval itself. Prepares the automation,
 *   launches the app, logs the active-window root, the interactive-window count and every window's
 *   type/active/focused flags and root identity under the `diagnose:` prefix, then polls up to 30 s
 *   for an app root (logging the first 5 polls and then one per 5 s). It never asserts, so the host
 *   can read the evidence and iterate without a failing instrument run.
 * - `groupDisposition`: the whole remaining section 10.3 flow in ONE instrumentation run. Args
 *   `target` (default 整组标记为重复), `maxForwardActions` (default 30000), `settleMillis`
 *   (default 120), `stallChecks` (default 20), `confirmTimeoutMillis` (default 3600000),
 *   `waitForIntakeMillis` (default 0), `cardScrollActions` (default 2000). In order: when
 *   `waitForIntakeMillis` is greater than 0, run
 *   the host-driven intake phase first (see the two paragraphs below); prepare the automation and
 *   reach the import review screen; read the authoritative database counts and the duplicate-status
 *   histogram in-process (read-only); traverse to the target with the `ACTION_SCROLL_FORWARD`
 *   fallback; open the 整组确认页 card and log its key texts, item count and 勾选候选 count; click
 *   确认整组标记 and wait, bounded, for the per-item outcomes to settle; read the same database counts
 *   again and log the delta. Every line carries the `groupDisposition:` prefix. The one-run shape is
 *   forced by the lifecycle: `am instrument` force-stops the app process when the run ends, so any
 *   UI state a run reaches is gone by the time the next run starts - the remaining flow cannot be
 *   split across the smaller modes, and the group entry is itself session-scoped (below).
 *
 * The group entry 整组标记为重复 is SESSION-SCOPED rather than merely list-position-scoped: the
 * affordance is gated on `view.lastIntakeSession?.inputRef`
 * (`app-ui/.../P503ImportReviewPresentation.kt:891`), and `lastIntakeSession` is written by exactly
 * one reducer event - the file-intake result (`app-ui/.../P503Reducer.kt:632`). It is therefore
 * rendered only when a file import completed inside the running app session. A run that traverses a
 * pre-existing candidate list to its end finds no group entry, so the acceptance run must import a
 * file while the instrumentation is already running.
 *
 * That import is driven from the HOST (the SAF system picker), and the host needs `uiautomator dump`
 * for it - which a live `UiAutomation` connection blocks. The intake phase therefore runs BEFORE the
 * mode's first automation call and touches no automation surface: it reads the database baseline
 * directly (plain `SQLiteDatabase`, no automation), brings the app up with an explicit Intent launch
 * instead of a shell `am start` (`shell()` runs through `UiAutomation.executeShellCommand` and would
 * create the very connection the host needs absent), logs the host's tab-selection instruction, and
 * polls `import_candidate` every 5 s until the count has risen above the baseline AND stayed
 * unchanged for `INTAKE_STABLE_POLLS` consecutive polls. Selecting the 导入 tab is a coordinate tap
 * on the host at the fixed import-tab centre for the managed AVD's 1080x2400 profile, because this
 * phase holds no automation tree and the test cannot inject input at all without the connection it
 * is deliberately not creating. The phase never fails the mode: a timeout is a logged finding and
 * the flow continues.
 *
 * Three defects of the first device run of this mode are fixed here, all of them on the path that
 * follows the intake phase:
 * 1. The intake wait treated the first count above the baseline as the end of the intake, but the
 *    intake commits in batches: the run detected the rise at +9 rows while the host read 50,022 and
 *    the import finished at 50,200, so the mode proceeded against a list that was still growing.
 *    [awaitHostIntake] now logs every observation and proceeds only after the count has been
 *    unchanged for `INTAKE_STABLE_POLLS` consecutive polls, inside the same `waitForIntakeMillis`
 *    bound.
 * 2. [reachImportReview] began with an `am start` and then always resolved the import tab. After the
 *    intake the app is already on the import tab with the candidate list rendered, and a selected
 *    tab is not clickable, so a coordinate fallback selected a different tab and the candidate list
 *    never rendered (the mode's second precondition failed after 60 s). It now returns immediately
 *    when the candidate list is already rendered and launches only when the app root is absent; the
 *    launch decision and the tab resolution themselves are those of the second run, below.
 * 3. 确认整组标记 is the group card's footer, and the card is inserted BELOW the affordance that opens
 *    it, so the confirm button can sit outside the current viewport. [confirmGroupDisposition] now
 *    performs up to `cardScrollActions` bounded `ACTION_SCROLL_FORWARD` actions (the same scrollable
 *    container selection as the traversal, progress logged every `CARD_SCROLL_PROGRESS_EVERY`
 *    actions) until the label appears, and keeps the logged-finding behaviour when it never does.
 *
 * The second device run of this mode (intake coordination correct: `intake stabilized:
 * import_candidate=50400 stablePolls=3`, exactly +200 candidate rows and +1,200 duplicate rows) still
 * failed to traverse, and exposed three further defects on the same path, fixed here:
 * 1. [reachImportReview] decided before the app window existed. After the SAF picker closed,
 *    `appRoot()` was momentarily null, so the mode took the launch branch (`import review path=launch
 *    (no app root)`) although the app was already on the import review screen. It now waits up to
 *    [APP_ROOT_WAIT_MILLIS] (polling every [POLL_MILLIS]) for the app root BEFORE deciding anything,
 *    checks the candidate list as soon as the root exists, and after issuing a launch waits again (up
 *    to [POST_LAUNCH_ROOT_WAIT_MILLIS]) and re-checks the candidate list before the tab is resolved.
 *    Every wait logs its outcome, so the branch the run took is readable in the evidence.
 * 2. The tab resolution was a tolerant label lookup that can never succeed here - the tab-bar nodes
 *    expose no text and no contentDescription (the run logged `import tab tolerant lookup (...) found
 *    no clickable node`, with an empty label) - followed by a coordinate fallback that picked the
 *    NEAREST clickable node, which was the 3rd tab (centre x=532) rather than the import tab. The tap
 *    switched the app away from the import tab, the candidate-list check passed on the still-rendered
 *    old frame, and the list then went away - which is why the scrollable container was lost
 *    immediately afterwards. The import tab is bottom-band candidate index [IMPORT_TAB_INDEX] (the
 *    4th tab, centre x=[IMPORT_TAB_FALLBACK_X] on the 1080x2400 profile): [resolveImportTab] collects
 *    the tab nodes by tab-bar geometry (bottom band [TAB_BAND_TOP]..[TAB_BAND_BOTTOM], width
 *    [TAB_WIDTH_MIN]..[TAB_WIDTH_MAX], regardless of clickability) and collapses them to one node per
 *    tab position ([collapseTabCandidates]: the same band and width window also hold each tab's inner
 *    decorations and the new-expense FAB, twenty raw candidates for four tabs in the review-screen
 *    dump, so the raw index 3 would be a decoration of the first tab), treats a non-clickable import
 *    tab as already selected and taps NOTHING, and after a tap that does not render the list retries
 *    the remaining bottom-band candidates in increasing distance from x=[IMPORT_TAB_FALLBACK_X],
 *    bounded to [TAB_MAX_ATTEMPTS] taps, logging every attempt and its outcome. The old
 *    nearest-clickable click survives only as the last resort for an empty bottom band, and says so in
 *    the evidence.
 * 3. A transiently lost scroll container aborted the traversal: the run logged `FINDING: scrollable
 *    node lost after 0 forward actions` and stopped with `forwardActions=0`. [forwardUntilTarget] and
 *    the card-footer helper [scrollToConfirmAffordance] now refetch the container up to
 *    [CONTAINER_REFETCH_RETRIES] times with [CONTAINER_REFETCH_RETRY_MILLIS] between attempts,
 *    logging every retry (`container refetch retry <n>/[CONTAINER_REFETCH_RETRIES]`) and the
 *    recovery, and only then report the lost node.
 *
 * These readings are measurement and acceptance evidence, not a product capability claim, and they
 * do not change the plan section 10.3 verdict (D-161 decision 4). A target node that is absent
 * after a completed traversal is reported as a logged finding so the host still gets the evidence.
 * The same holds for the database reads: a database that cannot be opened (or a query that fails)
 * is logged as a finding and the mode continues - no mode ever fails on a reading.
 */
@RunWith(AndroidJUnit4::class)
class ImportScaleTraversalInstrumentedTest {
    /**
     * API 33 moved `ACTION_SCROLL_TO_POSITION` off the `AccessibilityNodeInfo` int constants onto
     * an `AccessibilityAction` object; `performAction(int, Bundle)` still takes the action id.
     */
    private val scrollToPositionActionId = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id

    @Test
    fun probe() {
        val evidence = mutableListOf<String>()
        try {
            runProbe(evidence)
        } finally {
            appendEvidence(evidence)
        }
    }

    @Test
    fun scrollToEnd() {
        val evidence = mutableListOf<String>()
        try {
            runScrollToEnd(evidence)
        } finally {
            appendEvidence(evidence)
        }
    }

    @Test
    fun scrollForwardProbe() {
        val evidence = mutableListOf<String>()
        try {
            runScrollForwardProbe(evidence)
        } finally {
            appendEvidence(evidence)
        }
    }

    @Test
    fun diagnose() {
        val evidence = mutableListOf<String>()
        try {
            runDiagnose(evidence)
        } finally {
            appendEvidence(evidence)
        }
    }

    @Test
    fun groupDisposition() {
        val evidence = mutableListOf<String>()
        try {
            runGroupDisposition(evidence)
        } finally {
            appendEvidence(evidence)
        }
    }

    // ---- mode bodies ----

    private fun runProbe(evidence: MutableList<String>) {
        val log = evidenceLog(evidence)
        evidence += "== probe start epochMillis=${System.currentTimeMillis()}"
        prepareAutomation()
        reachImportReview(log)
        val container = selectScrollableContainer(log)
        if (container == null) {
            evidence += "FINDING: no scrollable node in the import review tree; probe stops here"
            evidence += "== probe end"
            return
        }
        evidence += "chosen actionList: ${describeActions(container)}"
        evidence += "chosen collectionInfo=${describeCollection(container)} isScrollable=${container.isScrollable}"
        evidence += "chosen visible window: ${visibleRowWindow(container)}"
        evidence += "== probe end"
    }

    private fun runScrollToEnd(evidence: MutableList<String>) {
        val log = evidenceLog(evidence)
        val target = stringArg(ARG_TARGET, DEFAULT_TARGET)
        val maxForwardActions = intArg(ARG_MAX_FORWARD_ACTIONS, DEFAULT_MAX_FORWARD_ACTIONS)
        val settleMillis = intArg(ARG_SETTLE_MILLIS, DEFAULT_SETTLE_MILLIS)
        val stallChecks = intArg(ARG_STALL_CHECKS, DEFAULT_STALL_CHECKS)
        evidence += "== scrollToEnd start epochMillis=${System.currentTimeMillis()}"
        evidence += "args target=$target maxForwardActions=$maxForwardActions settleMillis=$settleMillis stallChecks=$stallChecks"
        prepareAutomation()
        reachImportReview(log)
        val container = selectScrollableContainer(log)
        if (container == null) {
            evidence += "FINDING: no scrollable node; traversal not attempted"
            evidence += "== scrollToEnd end"
            return
        }
        val traversal = traverseToTarget(container, target, maxForwardActions, settleMillis, stallChecks, REFETCH_EVERY, PROGRESS_EVERY, log)
        val endRoot = appRoot()
        val targetNode = findByContentDescription(endRoot, target)
        val result = "forwardActions=${traversal.forwardActions} elapsedMs=${traversal.elapsedMs}"
        evidence += "traversal result target=$target found=${targetNode != null} $result"
        evidence += "traversal end visible window: ${visibleRowWindow(endRoot)}"
        if (targetNode == null) {
            evidence += "FINDING: target not present after traversal; no click performed"
            evidence += "== scrollToEnd end"
            return
        }
        evidence += "target node ${describeNode(targetNode)} label=${nodeLabel(targetNode)}"
        val windowBeforeClick = visibleRowWindow(endRoot)
        val clicked = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        evidence += "target click accepted=$clicked"
        val changed = waitFor({ visibleRowWindow(appRoot()) != windowBeforeClick }, CLICK_WAIT_MILLIS, POLL_MILLIS)
        evidence += "tree changed after click=$changed"
        evidence += "key texts after click: ${keyTexts(appRoot())}"
        evidence += "== scrollToEnd end"
    }

    private fun runScrollForwardProbe(evidence: MutableList<String>) {
        val log = evidenceLog(evidence)
        val actions = intArg(ARG_N, DEFAULT_FORWARD_PROBE_ACTIONS)
        evidence += "== scrollForwardProbe start epochMillis=${System.currentTimeMillis()}"
        evidence += "args n=$actions"
        prepareAutomation()
        reachImportReview(log)
        var container = selectScrollableContainer(log)
        if (container == null) {
            evidence += "FINDING: no scrollable node; forward probe not attempted"
            evidence += "== scrollForwardProbe end"
            return
        }
        log("before: ${visibleRowWindow(appRoot())}")
        var performed = 0
        var consecutiveFailures = 0
        while (performed < actions) {
            if (performed % FORWARD_PROBE_LOG_EVERY == 0) {
                container = chooseScrollableContainer(scrollableNodes(appRoot())) ?: container
            }
            val node = container
            if (node == null) {
                log("FINDING: scrollable node lost after $performed actions")
                break
            }
            val accepted = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            performed += 1
            consecutiveFailures = if (accepted) 0 else consecutiveFailures + 1
            if (!accepted && consecutiveFailures == 1) {
                logRefusal(log, node)
            }
            sleepQuietly(FORWARD_PROBE_SETTLE_MILLIS.toLong())
            if (performed % FORWARD_PROBE_LOG_EVERY == 0) {
                log("actions=$performed accepted=$accepted ${visibleRowWindow(appRoot())}")
            }
            if (consecutiveFailures >= CONSECUTIVE_FAILURE_LIMIT) {
                log("FINDING: ACTION_SCROLL_FORWARD refused $consecutiveFailures times in a row after $performed actions")
                break
            }
        }
        log("after: ${visibleRowWindow(appRoot())}")
        evidence += "== scrollForwardProbe end"
    }

    /**
     * The root-retrieval diagnostic. It prepares the automation, launches the app, sleeps briefly
     * and then reports what the automation can see - the active-window root, the interactive
     * window count and every window's type/active/focused flags and root identity - before polling
     * up to [DIAGNOSE_POLL_TOTAL_MILLIS] for an app root. Every line carries the [DIAGNOSE_PREFIX]
     * so the host can grep it out of logcat or the evidence file. It never asserts.
     */
    private fun runDiagnose(evidence: MutableList<String>) {
        evidence += "$DIAGNOSE_PREFIX start epochMillis=${System.currentTimeMillis()}"
        val prepared = attempt("prepareAutomation") { prepareAutomation() }
        evidence += "$DIAGNOSE_PREFIX $prepared ${describeAutomationFlags()}"
        val launched = attempt("launch $LAUNCH_COMMAND") { shell(LAUNCH_COMMAND) }
        evidence += "$DIAGNOSE_PREFIX $launched"
        sleepQuietly(DIAGNOSE_SETTLE_MILLIS.toLong())
        evidence += "$DIAGNOSE_PREFIX after ${DIAGNOSE_SETTLE_MILLIS}ms ${describeWindowState()}"
        val startNanos = System.nanoTime()
        var poll = 0
        var lastLoggedAtMillis = 0L
        var found = false
        while (!found && elapsedMillis(startNanos) < DIAGNOSE_POLL_TOTAL_MILLIS) {
            found = runCatching { appRoot() }.getOrNull() != null
            val elapsedMs = elapsedMillis(startNanos)
            if (poll < DIAGNOSE_POLL_LOG_FIRST || elapsedMs - lastLoggedAtMillis >= DIAGNOSE_POLL_LOG_PERIOD_MILLIS) {
                lastLoggedAtMillis = elapsedMs
                evidence += "$DIAGNOSE_PREFIX poll=$poll elapsedMs=$elapsedMs appRootFound=$found ${describeWindowState()}"
            }
            poll += 1
            if (!found) {
                sleepQuietly(DIAGNOSE_POLL_INTERVAL_MILLIS.toLong())
            }
        }
        evidence += "$DIAGNOSE_PREFIX done appRootFound=$found polls=$poll elapsedMs=${elapsedMillis(startNanos)}"
    }

    /**
     * The whole remaining section 10.3 flow in one instrumentation run (the class doc explains why it
     * cannot be split across runs, and why the optional intake phase must precede the first
     * automation call). Every line carries the [GROUP_DISPOSITION_PREFIX]. No reading fails the test:
     * a missing target, an unopenable database, a missing intake or a card that never settles is
     * logged as a finding, and the mode continues with whatever it can still read.
     */
    private fun runGroupDisposition(evidence: MutableList<String>) {
        val log: (String) -> Unit = { line -> evidence += "$GROUP_DISPOSITION_PREFIX $line" }
        val target = stringArg(ARG_TARGET, DEFAULT_TARGET)
        val maxForwardActions = intArg(ARG_MAX_FORWARD_ACTIONS, DEFAULT_GROUP_MAX_FORWARD_ACTIONS)
        val settleMillis = intArg(ARG_SETTLE_MILLIS, DEFAULT_GROUP_SETTLE_MILLIS)
        val stallChecks = intArg(ARG_STALL_CHECKS, DEFAULT_STALL_CHECKS)
        val confirmTimeoutMillis = intArg(ARG_CONFIRM_TIMEOUT_MILLIS, DEFAULT_CONFIRM_TIMEOUT_MILLIS)
        val waitForIntakeMillis = intArg(ARG_WAIT_FOR_INTAKE_MILLIS, DEFAULT_WAIT_FOR_INTAKE_MILLIS)
        val cardScrollActions = intArg(ARG_CARD_SCROLL_ACTIONS, DEFAULT_CARD_SCROLL_ACTIONS)
        log("start epochMillis=${System.currentTimeMillis()}")
        log(
            "args target=$target maxForwardActions=$maxForwardActions settleMillis=$settleMillis " +
                "stallChecks=$stallChecks confirmTimeoutMillis=$confirmTimeoutMillis waitForIntakeMillis=$waitForIntakeMillis " +
                "cardScrollActions=$cardScrollActions",
        )
        // The intake phase runs before the first automation call (class doc): the host drives the file
        // import with `uiautomator dump`, which a live automation connection blocks. It returns the
        // pre-intake database baseline, or null when the phase is switched off.
        val preIntake = if (waitForIntakeMillis > 0) awaitHostIntake(waitForIntakeMillis, log) else null
        prepareAutomation()
        reachImportReview(log)
        val container = selectScrollableContainer(log)
        if (container == null) {
            log("FINDING: no scrollable node in the import review tree; groupDisposition stops here")
            log("end")
            return
        }
        // The post-intake read is the baseline the group confirmation is measured against, so the
        // intake's own writes stay out of that delta; without an intake phase it is the plain
        // before-traversal read this mode has always logged.
        val baseline = readLedgerSnapshot(if (preIntake == null) "before" else "postIntake", log)
        if (preIntake != null) {
            logIntakeDelta(preIntake, baseline, log)
        }
        log("traversal start collectionInfo=${describeCollection(container)}")
        val startNanos = System.nanoTime()
        val traversal = traverseToTarget(container, target, maxForwardActions, settleMillis, stallChecks, GROUP_REFETCH_EVERY, GROUP_PROGRESS_EVERY, log)
        val elapsedMs = elapsedMillis(startNanos)
        val endRoot = appRoot()
        val targetNode = findByContentDescription(endRoot, target)
        val rate = ratePerSecond(traversal.forwardActions, elapsedMs)
        log("traversal total forwardActions=${traversal.forwardActions} elapsedMs=$elapsedMs actionsPerSecond=$rate rowsPerSecond=unavailable")
        log("traversal end visible window: ${visibleRowWindow(endRoot)}")
        if (targetNode == null) {
            log("FINDING: target=$target not present after ${traversal.forwardActions} forward actions; no click performed")
        } else {
            log("target node ${describeNode(targetNode)} label=${nodeLabel(targetNode)}")
            openGroupCard(targetNode, log)
            confirmGroupDisposition(confirmTimeoutMillis, cardScrollActions, settleMillis, log)
        }
        val after = readLedgerSnapshot("after", log)
        log("delta baseline=${if (preIntake == null) "before-traversal" else "post-intake"}")
        logSnapshotDelta(baseline, after, log)
        log("end")
    }

    /**
     * The host-driven intake phase, run before the mode's first automation call. Reads the pre-intake
     * database baseline, brings the app up without touching any automation surface, logs the host's
     * tab-selection instruction, then polls the `import_candidate` count every [INTAKE_POLL_MILLIS].
     *
     * A count that merely exceeds the baseline is NOT the intake: the intake commits in batches, so
     * the first rise can be observed while hundreds of rows are still being written (the device run
     * behind this fix detected at +9 while the host read 50,022 and the import finished at 50,200).
     * The wait therefore proceeds only once the count has stayed unchanged for [INTAKE_STABLE_POLLS]
     * consecutive polls after the rise, and it logs every observation, so the host can read the whole
     * commit curve. The stabilization polls live inside the same loop, so `waitForIntakeMillis` still
     * bounds the whole wait; a poll whose read fails breaks the stable run because an unreadable count
     * cannot evidence stability. A timeout is a logged finding, never a failure: the flow continues
     * and the traversal then reports what it finds. Returns the pre-intake baseline.
     */
    private fun awaitHostIntake(
        waitForIntakeMillis: Int,
        log: (String) -> Unit,
    ): LedgerSnapshot {
        log("intake phase start epochMillis=${System.currentTimeMillis()} waitForIntakeMillis=$waitForIntakeMillis")
        val baseline = readLedgerSnapshot("preIntake", log)
        launchAppWithoutAutomation(log)
        sleepQuietly(INTAKE_RENDER_MILLIS.toLong())
        val startNanos = System.nanoTime()
        var reference = baseline.importCandidates
        var latest: Long? = baseline.importCandidates
        var lastObserved: Long? = null
        var stablePolls = 0
        var poll = 0
        var detected = false
        var stabilized = false
        while (!stabilized && elapsedMillis(startNanos) < waitForIntakeMillis) {
            val count = countImportCandidates(log)
            poll += 1
            val elapsedMs = elapsedMillis(startNanos)
            if (count == null) {
                lastObserved = null
                stablePolls = 0
            } else {
                latest = count
                if (reference == null) {
                    reference = count
                    log("intake wait: the pre-intake count is unavailable; using the first readable count as the reference import_candidate=$count")
                }
                if (reference != null && count > reference) {
                    if (!detected) {
                        detected = true
                        log("intake detected: import_candidate $reference -> $count (+${count - reference})")
                    }
                    // The streak counts consecutive above-baseline observations of the SAME count, so a
                    // later rise, a drop back to the baseline or an unreadable poll all restart it.
                    stablePolls = if (count == lastObserved) stablePolls + 1 else 0
                    lastObserved = count
                    if (stablePolls >= INTAKE_STABLE_POLLS) {
                        stabilized = true
                        log("intake stabilized: import_candidate=$count stablePolls=$stablePolls")
                    }
                } else {
                    lastObserved = null
                    stablePolls = 0
                }
            }
            if (!stabilized) {
                log("waiting for intake: poll=$poll elapsedMs=$elapsedMs import_candidate=${count ?: "unavailable"} stablePolls=$stablePolls")
                sleepQuietly(INTAKE_POLL_MILLIS.toLong())
            }
        }
        val elapsedMs = elapsedMillis(startNanos)
        if (stabilized) {
            log("intake phase done elapsedMs=$elapsedMs detected=true stabilized=true import_candidate=$latest stablePolls=$stablePolls")
        } else if (detected) {
            log(
                "FINDING: intake rose above the baseline (import_candidate=$latest) but did not stay unchanged for " +
                    "$INTAKE_STABLE_POLLS consecutive polls within $waitForIntakeMillis ms; the count may still be committing",
            )
            log("intake phase done elapsedMs=$elapsedMs detected=true stabilized=false import_candidate=$latest")
        } else {
            log("FINDING: no intake observed within $waitForIntakeMillis ms; the group entry is expected to be absent")
            log("intake phase done elapsedMs=$elapsedMs detected=false stabilized=false import_candidate=${latest ?: "unavailable"}")
        }
        return baseline
    }

    /**
     * The app bring-up of the intake phase, with no automation surface touched. `shell()` reaches the
     * platform through `UiAutomation.executeShellCommand`, and obtaining the instrumentation's
     * `UiAutomation` creates its connection - the connection that blocks the host's `uiautomator
     * dump` while the host drives the SAF picker. An explicit Intent launch from the instrumentation's
     * target context starts the same activity in the same task as `am start -n
     * com.unifiedledger.android/.MainActivity` and touches no automation surface. The tab instruction
     * is logged for the host because the tap cannot be issued from here: input injection requires the
     * very connection this phase must not create.
     */
    private fun launchAppWithoutAutomation(log: (String) -> Unit) {
        val launched =
            attempt("intent launch of $MAIN_ACTIVITY_CLASS") {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val intent = Intent().setClassName(TARGET_PACKAGE, MAIN_ACTIVITY_CLASS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        log("intake wait: app bring-up $launched (no UiAutomation; a shell `am start` would create the automation connection)")
        log(
            "intake wait: ACTION REQUIRED ON HOST: select the $IMPORT_TAB_LABEL tab by a coordinate tap at " +
                "($IMPORT_TAB_FALLBACK_X, $IMPORT_TAB_FALLBACK_Y) - the fixed import-tab centre on the managed AVD's 1080x2400 profile - " +
                "then perform the SAF file import; this phase holds no automation connection, so it has no accessibility node to click " +
                "and cannot inject the tap itself",
        )
    }

    /**
     * The lightweight intake poll: one read-only open of the app database and a `COUNT(*)` of
     * `import_candidate`, the table the import intake appends to. A read that fails (for example
     * while the database is busy) is logged with its error and returns null; the wait keeps polling
     * and its progress lines report `unavailable` until the read recovers.
     */
    private fun countImportCandidates(log: (String) -> Unit): Long? =
        runCatching {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val path = context.getDatabasePath(DB_FILE_NAME)
            SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT COUNT(*) FROM import_candidate", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else null
                }
            }
        }.getOrElse { error ->
            log("intake wait: import_candidate count failed ${describeError(error)}")
            null
        }

    /** The intake's own effect on the authoritative counts, kept out of the group-confirmation delta. */
    private fun logIntakeDelta(
        preIntake: LedgerSnapshot,
        postIntake: LedgerSnapshot,
        log: (String) -> Unit,
    ) {
        log("delta intake import_candidate=${countDelta(preIntake.importCandidates, postIntake.importCandidates)}")
        log("delta intake import_source_record=${countDelta(preIntake.importSourceRecords, postIntake.importSourceRecords)}")
        log("delta intake import_duplicate_candidate=${countDelta(preIntake.duplicatePairs, postIntake.duplicatePairs)}")
        log("delta intake import_duplicate_status_history=${countDelta(preIntake.duplicateHistoryRows, postIntake.duplicateHistoryRows)}")
    }

    /**
     * Opens the 整组确认页 by clicking the traversal's target node and waiting (bounded by
     * [GROUP_CARD_WAIT_MILLIS]) for the card header or its confirm affordance. Logs every key text
     * (整组/共/待处置/已标记/失败), the 勾选候选 count and the item count the header discloses.
     * Returns whether the card was observed.
     */
    private fun openGroupCard(
        targetNode: AccessibilityNodeInfo,
        log: (String) -> Unit,
    ): Boolean {
        val startNanos = System.nanoTime()
        val clicked = clickNode(targetNode)
        log("group affordance click accepted=$clicked")
        val open = waitFor({ findGroupCardMarker(appRoot()) != null }, GROUP_CARD_WAIT_MILLIS, POLL_MILLIS)
        val root = appRoot()
        log("group card open=$open elapsedMs=${elapsedMillis(startNanos)}")
        log("group card key texts: ${keyTexts(root)}")
        val itemCountText = groupCardItemCountText(root)
        log("group card itemCount=${groupCardItemCount(root) ?: "unavailable"} itemCountText=${itemCountText ?: "none"}")
        log("group card confirm affordance=${describeNodeOrNone(findByText(root, GROUP_CARD_CONFIRM_LABEL))}")
        return open
    }

    /**
     * Clicks 确认整组标记 and waits, bounded by `confirmTimeoutMillis`, for the per-item outcomes to
     * settle. Clicking 整组标记为重复 inserts the group card below that affordance, so the card footer
     * can sit many rows below the current viewport; when the label is not in the current tree the mode
     * first performs up to `cardScrollActions` bounded `ACTION_SCROLL_FORWARD` actions
     * ([scrollToConfirmAffordance]) to bring it into view. A label that is still absent afterwards is a
     * logged finding and the wait is skipped, because nothing was submitted and no outcome can arrive.
     */
    private fun confirmGroupDisposition(
        confirmTimeoutMillis: Int,
        cardScrollActions: Int,
        settleMillis: Int,
        log: (String) -> Unit,
    ) {
        val confirmNode = findByText(appRoot(), GROUP_CARD_CONFIRM_LABEL) ?: scrollToConfirmAffordance(cardScrollActions, settleMillis, log)
        if (confirmNode == null) {
            log("FINDING: $GROUP_CARD_CONFIRM_LABEL not found in the tree after up to $cardScrollActions forward actions; group confirmation not performed")
            return
        }
        log("confirm affordance ${describeNode(confirmNode)} label=${nodeLabel(confirmNode)}")
        val clicked = clickNode(confirmNode)
        log("confirm click accepted=$clicked")
        awaitGroupCompletion(confirmTimeoutMillis, log)
    }

    /**
     * The bounded scroll that brings the card footer into the viewport after the group card was
     * inserted below the affordance that opened it. It uses the same scrollable-container selection as
     * the traversal ([chooseScrollableContainer]) and the same `ACTION_SCROLL_FORWARD` action,
     * refetching the container every [GROUP_REFETCH_EVERY] actions, logging progress every
     * [CARD_SCROLL_PROGRESS_EVERY] actions, and returning as soon as [GROUP_CARD_CONFIRM_LABEL]
     * appears. Returns null when the bound is reached, the scrollable node is lost, or the action is
     * refused [CONSECUTIVE_FAILURE_LIMIT] times in a row; a container that cannot be re-found is
     * retried like the traversal's ([refetchScrollableContainer]) before it is reported lost.
     */
    private fun scrollToConfirmAffordance(
        maxActions: Int,
        settleMillis: Int,
        log: (String) -> Unit,
    ): AccessibilityNodeInfo? {
        val startNanos = System.nanoTime()
        var container: AccessibilityNodeInfo? = null
        var performed = 0
        var consecutiveFailures = 0
        while (performed < maxActions) {
            if (performed % GROUP_REFETCH_EVERY == 0) {
                container = chooseScrollableContainer(scrollableNodes(appRoot())) ?: container
            }
            // Fix C applies here too: the card's own re-render can hide the container for a frame.
            if (container == null) {
                container = refetchScrollableContainer(performed, log)
            }
            val node = container
            if (node == null) {
                log("FINDING: no scrollable node while looking for $GROUP_CARD_CONFIRM_LABEL after $performed forward actions")
                return null
            }
            val accepted = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            performed += 1
            consecutiveFailures = if (accepted) 0 else consecutiveFailures + 1
            sleepQuietly(settleMillis.toLong())
            val found = findByText(appRoot(), GROUP_CARD_CONFIRM_LABEL)
            if (found != null) {
                log("card scroll reached $GROUP_CARD_CONFIRM_LABEL after $performed forward actions elapsedMs=${elapsedMillis(startNanos)}")
                return found
            }
            if (performed % CARD_SCROLL_PROGRESS_EVERY == 0) {
                log("card scroll progress forwardActions=$performed elapsedMs=${elapsedMillis(startNanos)} ${visibleRowWindow(appRoot())}")
            }
            if (consecutiveFailures >= CONSECUTIVE_FAILURE_LIMIT) {
                log("FINDING: ACTION_SCROLL_FORWARD refused $consecutiveFailures times in a row while looking for $GROUP_CARD_CONFIRM_LABEL after $performed forward actions")
                return null
            }
        }
        return null
    }

    /**
     * The bounded completion wait for the group confirmation. Polls every [GROUP_POLL_MILLIS],
     * logs progress every [GROUP_PROGRESS_LOG_MILLIS] (so the host can read the per-item rate), and
     * treats the loop as complete when a terminal per-item outcome is visible AND the outcome
     * signature has not changed for [GROUP_STABLE_CHECKS] consecutive polls. The default timeout is
     * sized for a 10,000-item group, which is processed item by item and can take a long time. When
     * no terminal marker can be detected within `confirmTimeoutMillis`, it falls back to a bounded
     * fixed wait of `confirmTimeoutMillis / [GROUP_FALLBACK_TIMEOUT_DIVISOR]` and says which path was
     * taken in the evidence.
     */
    private fun awaitGroupCompletion(
        confirmTimeoutMillis: Int,
        log: (String) -> Unit,
    ) {
        val startNanos = System.nanoTime()
        var lastSignature = ""
        var stableChecks = 0
        var terminalSeen = false
        var lastLoggedAtMillis = 0L
        var done = false
        while (!done && elapsedMillis(startNanos) < confirmTimeoutMillis) {
            val counts = collectOutcomeCounts(appRoot())
            terminalSeen = terminalSeen || counts.terminalCount > 0
            stableChecks = if (counts.signature == lastSignature) stableChecks + 1 else 0
            lastSignature = counts.signature
            val elapsedMs = elapsedMillis(startNanos)
            if (elapsedMs - lastLoggedAtMillis >= GROUP_PROGRESS_LOG_MILLIS) {
                lastLoggedAtMillis = elapsedMs
                log("confirm progress elapsedMs=$elapsedMs ${counts.describe()}")
            }
            done = terminalSeen && stableChecks >= GROUP_STABLE_CHECKS
            if (!done) {
                sleepQuietly(GROUP_POLL_MILLIS.toLong())
            }
        }
        val elapsedMs = elapsedMillis(startNanos)
        if (done) {
            log("confirm complete elapsedMs=$elapsedMs stableChecks=$stableChecks ${collectOutcomeCounts(appRoot()).describe()}")
            return
        }
        val fallbackMillis = confirmTimeoutMillis / GROUP_FALLBACK_TIMEOUT_DIVISOR
        log(
            "FINDING: no stable terminal outcome marker within $confirmTimeoutMillis ms (terminalSeen=$terminalSeen); " +
                "falling back to a bounded fixed wait of $fallbackMillis ms (confirmTimeoutMillis / $GROUP_FALLBACK_TIMEOUT_DIVISOR)",
        )
        sleepQuietly(fallbackMillis.toLong())
        log("fixed wait complete elapsedMs=${elapsedMillis(startNanos)} ${collectOutcomeCounts(appRoot()).describe()}")
    }

    // ---- group card readings ----

    /** The per-item outcome counts of the visible card window, with its stability signature. */
    private data class GroupOutcomeCounts(
        val pending: Int,
        val marked: Int,
        val failed: Int,
        val signature: String,
    ) {
        val terminalCount: Int get() = marked + failed

        fun describe(): String = "outcomeLines 待处置=$pending 已标记=$marked 失败=$failed signature=$signature"
    }

    private fun collectOutcomeCounts(root: AccessibilityNodeInfo?): GroupOutcomeCounts {
        val texts = collectNodes(root) { node -> isOutcomeText(nodeText(node)) }.map { node -> nodeText(node) }
        return GroupOutcomeCounts(
            pending = texts.count { text -> text == OUTCOME_PENDING_TEXT },
            marked = texts.count { text -> text.startsWith(OUTCOME_MARKED_PREFIX) },
            failed = texts.count { text -> text.startsWith(OUTCOME_FAILED_PREFIX) },
            signature = texts.distinct().sorted().joinToString(separator = "|"),
        )
    }

    private fun isOutcomeText(text: String): Boolean = text == OUTCOME_PENDING_TEXT || text.startsWith(OUTCOME_MARKED_PREFIX) || text.startsWith(OUTCOME_FAILED_PREFIX)

    private fun findGroupCardMarker(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? = findByText(root, GROUP_CARD_TITLE) ?: findByText(root, GROUP_CARD_CONFIRM_LABEL)

    /** The card header's disclosure line (`……共 N 条。`), which is where the card states its item count. */
    private fun groupCardItemCountText(root: AccessibilityNodeInfo?): String? =
        collectNodes(root) { node -> nodeText(node).contains(GROUP_ITEM_COUNT_PREFIX) && nodeText(node).endsWith(GROUP_ITEM_COUNT_SUFFIX) }
            .firstOrNull()
            ?.let { node -> nodeText(node) }

    private fun groupCardItemCount(root: AccessibilityNodeInfo?): Long? {
        val text = groupCardItemCountText(root) ?: return null
        val afterPrefix = text.substringAfter(GROUP_ITEM_COUNT_PREFIX)
        val countText = afterPrefix.substringBefore(GROUP_ITEM_COUNT_SUFFIX.trimStart())
        return countText.trim().toLongOrNull()
    }

    // ---- authoritative database readings (in-process, read-only) ----

    /** One authoritative reading of the app database; a null field is a reading that failed. */
    private data class LedgerSnapshot(
        val importCandidates: Long?,
        val importSourceRecords: Long?,
        val ledgerTransactions: Long?,
        val duplicatePairs: Long?,
        val duplicateHistoryRows: Long?,
        val duplicateStatusHistogram: List<String>,
    )

    /**
     * Reads the app database read-only through the platform `SQLiteDatabase` - the authoritative
     * evidence for "how many candidates exist" and "which duplicate statuses were actually written",
     * as opposed to UI copy. The database file is the app's own `ledger.db`
     * (`InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath`), the same file
     * the product's driver opens. A database that cannot be opened, or a query that fails, is logged
     * as a finding and reported as a null reading - this never fails the test.
     */
    private fun readLedgerSnapshot(
        label: String,
        log: (String) -> Unit,
    ): LedgerSnapshot {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = context.getDatabasePath(DB_FILE_NAME)
        val sizeBytes = if (path.exists()) path.length() else -1L
        log("db $label file=${path.name} exists=${path.exists()} sizeBytes=$sizeBytes")
        val database =
            runCatching { SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READONLY) }
                .getOrElse { error ->
                    log("FINDING: db $label open failed ${describeError(error)}; counts unavailable, continuing")
                    return LedgerSnapshot(null, null, null, null, null, emptyList())
                }
        return database.use { db ->
            val snapshot =
                LedgerSnapshot(
                    importCandidates = countRows(db, "import_candidate", label, log),
                    importSourceRecords = countRows(db, "import_source_record", label, log),
                    ledgerTransactions = countRows(db, "ledger_transaction", label, log),
                    duplicatePairs = countRows(db, "import_duplicate_candidate", label, log),
                    duplicateHistoryRows = countRows(db, "import_duplicate_status_history", label, log),
                    duplicateStatusHistogram = duplicateStatusHistogram(db, label, log),
                )
            log("db $label ${describeSnapshot(snapshot)}")
            snapshot
        }
    }

    private fun countRows(
        db: SQLiteDatabase,
        table: String,
        label: String,
        log: (String) -> Unit,
    ): Long? =
        runCatching {
            db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
        }.getOrElse { error ->
            log("FINDING: db $label count($table) failed ${describeError(error)}")
            null
        }

    /**
     * The duplicate-status histogram: the latest row per `(ledger_id, candidate_id)` pair, grouped by
     * status. The resolved SQL is logged so the host sees the exact query the reading came from.
     */
    private fun duplicateStatusHistogram(
        db: SQLiteDatabase,
        label: String,
        log: (String) -> Unit,
    ): List<String> {
        log("db $label duplicateStatusSql=$DUPLICATE_STATUS_HISTOGRAM_SQL")
        return runCatching {
            val entries = mutableListOf<String>()
            db.rawQuery(DUPLICATE_STATUS_HISTOGRAM_SQL, null).use { cursor ->
                while (cursor.moveToNext()) {
                    entries += "${cursor.getString(0)}=${cursor.getLong(1)}"
                }
            }
            entries
        }.getOrElse { error ->
            log("FINDING: db $label duplicate status histogram failed ${describeError(error)}")
            emptyList()
        }
    }

    private fun describeSnapshot(snapshot: LedgerSnapshot): String {
        val histogram = snapshot.duplicateStatusHistogram.joinToString(separator = ",")
        return "import_candidate=${snapshot.importCandidates} import_source_record=${snapshot.importSourceRecords} " +
            "ledger_transaction=${snapshot.ledgerTransactions} import_duplicate_candidate=${snapshot.duplicatePairs} " +
            "import_duplicate_status_history=${snapshot.duplicateHistoryRows} duplicateStatusHistogram=[$histogram]"
    }

    /** The explicit before/after delta; the CONFIRMED_DUPLICATE delta is the 正式效果次数 reading. */
    private fun logSnapshotDelta(
        before: LedgerSnapshot,
        after: LedgerSnapshot,
        log: (String) -> Unit,
    ) {
        log("delta import_candidate=${countDelta(before.importCandidates, after.importCandidates)}")
        log("delta import_source_record=${countDelta(before.importSourceRecords, after.importSourceRecords)}")
        log("delta ledger_transaction=${countDelta(before.ledgerTransactions, after.ledgerTransactions)}")
        log("delta import_duplicate_candidate=${countDelta(before.duplicatePairs, after.duplicatePairs)}")
        log("delta import_duplicate_status_history=${countDelta(before.duplicateHistoryRows, after.duplicateHistoryRows)}")
        log("delta duplicateStatusHistogram=[${histogramDelta(before.duplicateStatusHistogram, after.duplicateStatusHistogram)}]")
        val confirmedDelta = histogramCount(after.duplicateStatusHistogram, CONFIRMED_DUPLICATE_STATUS) - histogramCount(before.duplicateStatusHistogram, CONFIRMED_DUPLICATE_STATUS)
        log("delta CONFIRMED_DUPLICATE=$confirmedDelta (the authoritative count of group items that reached the confirmed-duplicate status)")
    }

    private fun countDelta(
        before: Long?,
        after: Long?,
    ): String =
        if (before == null || after == null) {
            "unavailable(before=$before after=$after)"
        } else {
            "${after - before} (before=$before after=$after)"
        }

    private fun histogramDelta(
        before: List<String>,
        after: List<String>,
    ): String {
        val statuses = (before + after).map { entry -> entry.substringBefore('=') }.distinct().sorted()
        return statuses.joinToString(separator = ",") { status ->
            val beforeCount = histogramCount(before, status)
            val afterCount = histogramCount(after, status)
            "$status=${afterCount - beforeCount}($beforeCount->$afterCount)"
        }
    }

    private fun histogramCount(
        entries: List<String>,
        status: String,
    ): Long {
        val entry = entries.firstOrNull { candidate -> candidate.substringBefore('=') == status } ?: return 0L
        return entry.substringAfter('=').toLongOrNull() ?: 0L
    }

    /**
     * The shared traversal every scrolling mode uses: `ACTION_SCROLL_TO_POSITION` at a very large row
     * index when the node offers it, then the [forwardUntilTarget] fallback. Returns the action
     * count, the elapsed time and whether the position action was offered at all, so `scrollToEnd`
     * and `groupDisposition` log identical readings.
     */
    private fun traverseToTarget(
        container: AccessibilityNodeInfo,
        target: String,
        maxForwardActions: Int,
        settleMillis: Int,
        stallChecks: Int,
        refetchEvery: Int,
        progressEvery: Int,
        log: (String) -> Unit,
    ): TraversalResult {
        val startNanos = System.nanoTime()
        val offersScrollToPosition = container.actionList.any { action -> action.id == scrollToPositionActionId }
        var forwardActions = 0
        var targetNode: AccessibilityNodeInfo? = null
        if (offersScrollToPosition) {
            val arguments = Bundle()
            arguments.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, LAST_ITEM_ROW_INDEX)
            val accepted = container.performAction(scrollToPositionActionId, arguments)
            sleepQuietly(settleMillis.toLong())
            targetNode = findByContentDescription(appRoot(), target)
            log("ACTION_SCROLL_TO_POSITION row=$LAST_ITEM_ROW_INDEX accepted=$accepted")
            log("after scrollToPosition elapsedMs=${elapsedMillis(startNanos)} targetFound=${targetNode != null}")
        } else {
            log("FINDING: node does not offer ACTION_SCROLL_TO_POSITION; ACTION_SCROLL_FORWARD fallback follows")
        }
        if (targetNode == null) {
            forwardActions = forwardUntilTarget(target, maxForwardActions, settleMillis, stallChecks, refetchEvery, progressEvery, log)
        }
        val elapsedMs = elapsedMillis(startNanos)
        log("traversal done elapsedMs=$elapsedMs forwardActions=$forwardActions scrollToPositionOffered=$offersScrollToPosition")
        return TraversalResult(forwardActions = forwardActions, elapsedMs = elapsedMs, scrollToPositionOffered = offersScrollToPosition)
    }

    /** One traversal's readings, shared by `scrollToEnd` and `groupDisposition`. */
    private data class TraversalResult(
        val forwardActions: Int,
        val elapsedMs: Long,
        val scrollToPositionOffered: Boolean,
    )

    /**
     * The fallback traversal: repeated `ACTION_SCROLL_FORWARD` with `settleMillis` between actions,
     * the root re-fetched every [refetchEvery] actions to re-locate the scrollable node, to check for
     * [target] and to log progress every [progressEvery] actions (action count, elapsed time, rate
     * and the visible window). Two guards stop a traversal that no longer advances: the stall guard
     * ([stallChecks] consecutive progress checks with an unchanged last visible row) and the
     * consecutive-refusal guard ([CONSECUTIVE_FAILURE_LIMIT], whose first refusal is logged with the
     * action id and the node). A container that cannot be re-found at all is retried
     * [CONTAINER_REFETCH_RETRIES] times by [refetchScrollableContainer] before the traversal gives up
     * on it, so a frame that has not caught up with the list does not end the traversal. Returns the
     * number of forward actions performed.
     */
    private fun forwardUntilTarget(
        target: String,
        maxForwardActions: Int,
        settleMillis: Int,
        stallChecks: Int,
        refetchEvery: Int,
        progressEvery: Int,
        log: (String) -> Unit,
    ): Int {
        val startNanos = System.nanoTime()
        var container: AccessibilityNodeInfo? = null
        var performed = 0
        var consecutiveFailures = 0
        var stalledChecks = 0
        var lastRow: String? = null
        while (performed < maxForwardActions) {
            if (performed % refetchEvery == 0) {
                container = chooseScrollableContainer(scrollableNodes(appRoot())) ?: container
            }
            // Fix C: a container that is momentarily absent is not the end of the traversal - the tree
            // is re-read up to CONTAINER_REFETCH_RETRIES times before the node is declared lost.
            if (container == null) {
                container = refetchScrollableContainer(performed, log)
            }
            val node = container
            if (node == null) {
                log("FINDING: scrollable node lost after $performed forward actions")
                return performed
            }
            val accepted = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            performed += 1
            consecutiveFailures = if (accepted) 0 else consecutiveFailures + 1
            if (!accepted && consecutiveFailures == 1) {
                logRefusal(log, node)
            }
            sleepQuietly(settleMillis.toLong())
            var fresh: AccessibilityNodeInfo? = null
            if (performed % refetchEvery == 0) {
                fresh = appRoot()
                if (findByContentDescription(fresh, target) != null) {
                    log("target appeared after $performed forward actions")
                    return performed
                }
            }
            if (performed % progressEvery == 0) {
                val window = visibleWindow(fresh ?: appRoot())
                val elapsedMs = elapsedMillis(startNanos)
                log("progress forwardActions=$performed elapsedMs=$elapsedMs actionsPerSecond=${ratePerSecond(performed, elapsedMs)} rowsPerSecond=unavailable ${describeVisibleWindow(window)}")
                stalledChecks = if (window.last == lastRow) stalledChecks + 1 else 0
                lastRow = window.last
                if (stalledChecks >= stallChecks) {
                    log("FINDING: traversal stalled at ${describeVisibleWindow(window)} after $performed forward actions ($stalledChecks consecutive unchanged checks)")
                    return performed
                }
            }
            if (consecutiveFailures >= CONSECUTIVE_FAILURE_LIMIT) {
                log("FINDING: ACTION_SCROLL_FORWARD refused $consecutiveFailures times in a row after $performed actions")
                return performed
            }
        }
        return performed
    }

    /**
     * Re-locates the scrollable container after it disappeared from the tree (Fix C): the tree is
     * re-read up to [CONTAINER_REFETCH_RETRIES] times with [CONTAINER_REFETCH_RETRY_MILLIS] between
     * attempts, every retry is logged as `container refetch retry <n>/[CONTAINER_REFETCH_RETRIES]`,
     * and a recovery is logged too. The caller reports the lost node only when this returns null. A
     * container that is transiently absent - a re-render, an animation, a frame that has not caught
     * up with the traversal - used to abort the traversal on the spot: the second device run logged
     * `FINDING: scrollable node lost after 0 forward actions` and stopped with zero actions.
     */
    private fun refetchScrollableContainer(
        performed: Int,
        log: (String) -> Unit,
    ): AccessibilityNodeInfo? {
        var retry = 0
        while (retry < CONTAINER_REFETCH_RETRIES) {
            retry += 1
            log("container refetch retry $retry/$CONTAINER_REFETCH_RETRIES after $performed forward actions")
            sleepQuietly(CONTAINER_REFETCH_RETRY_MILLIS.toLong())
            val container = chooseScrollableContainer(scrollableNodes(appRoot()))
            if (container != null) {
                log("container refetch recovered after $performed forward actions (retry $retry/$CONTAINER_REFETCH_RETRIES)")
                return container
            }
        }
        return null
    }

    /** The first refusal of a forward action carries the action id and the node it was sent to. */
    private fun logRefusal(
        log: (String) -> Unit,
        node: AccessibilityNodeInfo,
    ) {
        val actionId = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        log("first ACTION_SCROLL_FORWARD refusal actionId=$actionId node=${describeNode(node)} label=${nodeLabel(node)}")
    }

    /** A one-decimal rate, locale-independent so the evidence parses the same on every host. */
    private fun ratePerSecond(
        count: Int,
        elapsedMs: Long,
    ): String = String.format(Locale.ROOT, "%.1f", count * 1000.0 / elapsedMs.coerceAtLeast(1L).toDouble())

    // ---- app entry and screen reachability ----

    /**
     * Brings the app to the import review screen, in a deliberate order, because a run that follows
     * the host-driven intake phase is already on that screen with the candidate list rendered:
     * 1. The app root is awaited FIRST, up to [APP_ROOT_WAIT_MILLIS] with a [POLL_MILLIS] poll (Fix A):
     *    the second device run decided while the root was momentarily absent - the SAF picker had just
     *    closed - and took the launch branch although the app was already on the import review screen.
     * 2. An already-rendered candidate list returns immediately and taps nothing. The selected import
     *    tab is not clickable, so the old coordinate fallback picked a *different* tab and the
     *    candidate list never rendered.
     * 3. The `am start` launch is issued only when the app root is still absent after that wait; an app
     *    that is already reachable keeps its screen and its session state (the group entry is
     *    session-scoped). After the launch the root is awaited again (up to
     *    [POST_LAUNCH_ROOT_WAIT_MILLIS]) and the candidate list is re-checked before the tab is
     *    resolved.
     * 4. Only then is the import tab resolved ([resolveImportTab], by tab-bar geometry) and the
     *    candidate list awaited.
     * Every wait logs its outcome, so the host can read which branch the run took.
     */
    private fun reachImportReview(log: (String) -> Unit) {
        val rootPresent = awaitAppRoot(APP_ROOT_WAIT_MILLIS, "pre-decision", log)
        if (rootPresent && findCandidateRow(appRoot()) != null) {
            log("already on import review: ${visibleRowWindow(appRoot())}")
            return
        }
        if (rootPresent) {
            log("import review path=existing-app-root (the app root is reachable; launch skipped)")
        } else {
            log("import review path=launch (no app root after $APP_ROOT_WAIT_MILLIS ms)")
            log("launching: $LAUNCH_COMMAND")
            shell(LAUNCH_COMMAND)
            val reachable = awaitAppRoot(POST_LAUNCH_ROOT_WAIT_MILLIS, "post-launch", log)
            assertTrue(
                "MainActivity window did not become reachable within $POST_LAUNCH_ROOT_WAIT_MILLIS ms; observed: ${observedState()}",
                reachable,
            )
        }
        if (findCandidateRow(appRoot()) != null) {
            log("already on import review after the app root wait: ${visibleRowWindow(appRoot())}")
            return
        }
        val resolved = resolveImportTab(log)
        val rendered = resolved || waitFor({ findCandidateRow(appRoot()) != null }, LIST_WAIT_MILLIS, POLL_MILLIS)
        assertTrue(
            "import candidate list did not render within $LIST_WAIT_MILLIS ms; observed: ${observedState()}",
            rendered,
        )
        log("candidate list rendered: ${visibleRowWindow(appRoot())}")
    }

    /** Waits for the app window and logs the outcome, so every launch decision is readable in the evidence. */
    private fun awaitAppRoot(
        timeoutMillis: Int,
        label: String,
        log: (String) -> Unit,
    ): Boolean {
        val startNanos = System.nanoTime()
        val found = waitFor({ appTreeReachable() }, timeoutMillis, POLL_MILLIS)
        log("app root wait ($label): timeoutMillis=$timeoutMillis found=$found elapsedMs=${elapsedMillis(startNanos)}")
        return found
    }

    private fun appTreeReachable(): Boolean = appRoot() != null

    /**
     * The import tab, resolved by tab-bar geometry (Fix B) instead of the dead label lookup - the
     * tab-bar nodes expose no text and no contentDescription on this build, so a lookup can never
     * succeed - and instead of the nearest-clickable coordinate fallback, which picked the 3rd tab
     * (centre x=532) rather than the import tab and switched the app away from the import screen.
     *
     * The bottom band ([TAB_BAND_TOP]..[TAB_BAND_BOTTOM]) is scanned for nodes between
     * [TAB_WIDTH_MIN] and [TAB_WIDTH_MAX] wide, regardless of clickability (the selected tab is not
     * clickable), sorted by their left edge, and every raw candidate is logged with its index, bounds,
     * centre and clickable flag. The raw list is then collapsed to one node per tab position
     * ([collapseTabCandidates] - the real tree carries inner decorations and the FAB inside the same
     * band and width window), and every collapsed candidate is logged the same way. The import tab is
     * bottom-band index [IMPORT_TAB_INDEX] (the 4th tab, centre x=[IMPORT_TAB_FALLBACK_X] on the
     * managed AVD's 1080x2400 profile):
     * - a non-clickable import tab IS the already-selected tab: nothing is tapped at all;
     * - a clickable one is clicked and the candidate list is awaited for [TAB_LIST_WAIT_MILLIS];
     * - when the list does not render, the remaining bottom-band candidates are retried in increasing
     *   distance from x=[IMPORT_TAB_FALLBACK_X], bounded to [TAB_MAX_ATTEMPTS] taps in total, every
     *   attempt and its outcome logged, and the resolution stops as soon as the list renders.
     * An empty bottom band is the only case that still falls back to the nearest clickable node to
     * ([IMPORT_TAB_FALLBACK_X], [IMPORT_TAB_FALLBACK_Y]), and it says so in the evidence.
     * Returns whether the candidate list rendered during the resolution.
     */
    private fun resolveImportTab(log: (String) -> Unit): Boolean {
        val raw = bottomBandTabNodes(appRoot())
        log("import tab resolution: bottom band top>=$TAB_BAND_TOP bottom<=$TAB_BAND_BOTTOM width=$TAB_WIDTH_MIN..$TAB_WIDTH_MAX rawCandidates=${raw.size}")
        for ((index, node) in raw.withIndex()) {
            log("import tab raw candidate[$index] ${describeTabCandidate(node)}")
        }
        val candidates = collapseTabCandidates(raw, log)
        for ((index, node) in candidates.withIndex()) {
            log("import tab candidate[$index] ${describeTabCandidate(node)}")
        }
        if (candidates.isEmpty()) {
            log(
                "FINDING: the bottom band yielded no tab candidate; last resort = the nearest clickable node to " +
                    "($IMPORT_TAB_FALLBACK_X, $IMPORT_TAB_FALLBACK_Y)",
            )
            clickNearestTo(IMPORT_TAB_FALLBACK_X, IMPORT_TAB_FALLBACK_Y, log)
            return false
        }
        val importTab = candidates.getOrNull(IMPORT_TAB_INDEX)
        if (importTab != null && !importTab.isClickable) {
            log("import tab already selected (not clickable); no tap")
            return false
        }
        if (importTab == null) {
            log("FINDING: the bottom band yielded only ${candidates.size} tab candidate(s); the import tab index $IMPORT_TAB_INDEX is unavailable")
        } else {
            log("import tab choice: bottom-band index $IMPORT_TAB_INDEX of ${candidates.size} ${describeTabCandidate(importTab)}")
        }
        val attempts = orderedTabAttempts(candidates)
        var taps = 0
        for (node in attempts) {
            if (taps >= TAB_MAX_ATTEMPTS) {
                break
            }
            if (!node.isClickable) {
                log("import tab attempt skipped (not clickable, a selected tab cannot be tapped): ${describeTabCandidate(node)}")
                continue
            }
            taps += 1
            log("import tab attempt $taps/$TAB_MAX_ATTEMPTS tap ${describeTabCandidate(node)}")
            val accepted = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            log("import tab attempt $taps/$TAB_MAX_ATTEMPTS click accepted=$accepted")
            val attemptStartNanos = System.nanoTime()
            val rendered = waitFor({ findCandidateRow(appRoot()) != null }, TAB_LIST_WAIT_MILLIS, POLL_MILLIS)
            log("import tab attempt $taps/$TAB_MAX_ATTEMPTS rendered=$rendered elapsedMs=${elapsedMillis(attemptStartNanos)}")
            if (rendered) {
                return true
            }
        }
        log("FINDING: no bottom-band tab attempt rendered the candidate list within $TAB_LIST_WAIT_MILLIS ms each (taps=$taps)")
        return false
    }

    /**
     * The tab-bar nodes of the bottom band, in left-to-right order. The band and the width window are
     * the geometry fact measured on the managed AVD's 1080x2400 profile (four 183 px wide tab nodes
     * whose tops sit at y=2095 and bottoms at y=2305); clickability is deliberately not part of the
     * filter, because the SELECTED tab is not clickable and still has to be identifiable.
     */
    private fun bottomBandTabNodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        val inBand =
            collectNodes(root) { node ->
                val bounds = boundsOf(node)
                bounds.top >= TAB_BAND_TOP &&
                    bounds.bottom <= TAB_BAND_BOTTOM &&
                    bounds.width() >= TAB_WIDTH_MIN &&
                    bounds.width() <= TAB_WIDTH_MAX
            }
        return inBand.sortedBy { node -> boundsOf(node).left }
    }

    /**
     * Collapses the raw band candidates to ONE node per tab position, in left-to-right order, which is
     * what makes the index-[IMPORT_TAB_INDEX] fact hold. A device dump of the review screen (the same
     * tree the second run traversed) shows why the band+width filter alone is not enough: each tab item
     * carries an inner decoration of the same or a contained box (the item is 183x210 at
     * y=2095..2305, its indicator decorations are 126x127 boxes inside it), and the new-expense FAB
     * (147x147 at x=870..1017) also falls inside the band and the width window - twenty raw candidates
     * for four tabs, so raw index 3 would be a decoration of the first tab and the mode would conclude
     * "already selected" without tapping anything.
     *
     * A candidate whose bounds are contained in (or identical to) a candidate already kept is therefore
     * dropped, and a clickable node at a position replaces a non-clickable node kept there - so each tab
     * position keeps the node that can actually be tapped. The FAB survives as the last candidate, to the
     * right of every tab, so it never shifts the index of the import tab; it is only reachable as a
     * retry attempt, and every drop and replacement is logged.
     */
    private fun collapseTabCandidates(
        raw: List<AccessibilityNodeInfo>,
        log: (String) -> Unit,
    ): List<AccessibilityNodeInfo> {
        val kept = mutableListOf<AccessibilityNodeInfo>()
        for (node in raw) {
            val bounds = boundsOf(node)
            val containerIndex = kept.indexOfFirst { other -> containsBounds(boundsOf(other), bounds) }
            if (containerIndex < 0) {
                kept += node
                continue
            }
            if (!kept[containerIndex].isClickable && node.isClickable) {
                log("import tab candidate replaced (clickable node at the same position): ${describeTabCandidate(node)}")
                kept[containerIndex] = node
            } else {
                log("import tab candidate dropped (inside ${boundsOf(kept[containerIndex])}): ${describeTabCandidate(node)}")
            }
        }
        return kept.sortedBy { node -> boundsOf(node).left }
    }

    /** Whether [inner] lies inside [outer] (identical bounds included, so duplicates collapse). */
    private fun containsBounds(
        outer: Rect,
        inner: Rect,
    ): Boolean = outer.left <= inner.left && outer.top <= inner.top && outer.right >= inner.right && outer.bottom >= inner.bottom

    /**
     * The tap order of [resolveImportTab]: the import tab (bottom-band index [IMPORT_TAB_INDEX]) first
     * when it exists, then every remaining candidate by increasing distance of its centre from
     * x=[IMPORT_TAB_FALLBACK_X]. Candidates that are not clickable are skipped by the caller.
     */
    private fun orderedTabAttempts(candidates: List<AccessibilityNodeInfo>): List<AccessibilityNodeInfo> {
        val importTab = candidates.getOrNull(IMPORT_TAB_INDEX)
        val remaining =
            candidates
                .filter { node -> node !== importTab }
                .sortedBy { node -> abs(boundsOf(node).centerX() - IMPORT_TAB_FALLBACK_X) }
        return if (importTab == null) remaining else listOf(importTab) + remaining
    }

    /** One tab candidate in the evidence: bounds, centre, width, clickability, class and (empty) label. */
    private fun describeTabCandidate(node: AccessibilityNodeInfo): String {
        val bounds = boundsOf(node)
        return "bounds=$bounds center=(${bounds.centerX()}, ${bounds.centerY()}) width=${bounds.width()} " +
            "clickable=${node.isClickable} class=${node.className} label=${nodeLabel(node)}"
    }

    private fun clickNearestTo(
        pointX: Int,
        pointY: Int,
        log: (String) -> Unit,
    ) {
        val clickable = collectNodes(appRoot()) { node -> node.isClickable }
        val nearest = clickable.minByOrNull { node -> distanceSquared(boundsOf(node), pointX, pointY) }
        if (nearest == null) {
            log("FINDING: coordinate fallback found no clickable node near ($pointX, $pointY)")
            return
        }
        val bounds = boundsOf(nearest)
        log("coordinate fallback target label=${nodeLabel(nearest)} class=${nearest.className}")
        log("coordinate fallback bounds=$bounds center=(${bounds.centerX()}, ${bounds.centerY()})")
        val clicked = nearest.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        log("coordinate fallback click accepted=$clicked")
    }

    private fun distanceSquared(
        bounds: Rect,
        pointX: Int,
        pointY: Int,
    ): Long {
        val deltaX = (bounds.centerX() - pointX).toLong()
        val deltaY = (bounds.centerY() - pointY).toLong()
        return deltaX * deltaX + deltaY * deltaY
    }

    // ---- root retrieval and diagnostics ----

    private var automationPrepared = false

    private fun uiAutomation(): UiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation

    /**
     * Sets `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` on the instrumentation's own `UiAutomation` once per
     * test process. The device run behind D-161 showed that service info carrying `flags=0`, which
     * leaves `UiAutomation.windows` empty and `rootInActiveWindow` unable to see the app window even
     * while the activity is displayed; the flag is the documented prerequisite for both. Called at
     * the start of every mode; `appRoot` calls it too, so a tree read can never precede it.
     */
    private fun prepareAutomation(): UiAutomation {
        val automation = uiAutomation()
        if (automationPrepared) {
            return automation
        }
        val info = automation.serviceInfo ?: return automation
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        automationPrepared = true
        return automation
    }

    /**
     * The app's window root, or null when the app is not on screen. `rootInActiveWindow` is the
     * cheapest source and is used when it belongs to [TARGET_PACKAGE]; otherwise the interactive
     * window list is scanned for a window whose root belongs to the app, preferring a window that
     * is active or focused. Every tree read in every mode goes through this method, so no node is
     * ever read against a root fetched at a different time.
     */
    private fun appRoot(): AccessibilityNodeInfo? {
        val automation = prepareAutomation()
        val activeRoot = automation.rootInActiveWindow
        if (activeRoot?.packageName?.toString() == TARGET_PACKAGE) {
            return activeRoot
        }
        var fallback: AccessibilityNodeInfo? = null
        val windows = runCatching { automation.windows }.getOrNull().orEmpty()
        for (window in windows) {
            val windowRoot = window.root ?: continue
            if (windowRoot.packageName?.toString() != TARGET_PACKAGE) {
                continue
            }
            if (window.isActive || window.isFocused) {
                return windowRoot
            }
            if (fallback == null) {
                fallback = windowRoot
            }
        }
        return fallback
    }

    /**
     * The one-line state a failed precondition carries: how many interactive windows the automation
     * sees, which packages they belong to, what the active-window root is, and whether the import
     * tab is currently reachable. Never throws; a broken read is reported as text.
     */
    private fun observedState(): String =
        runCatching {
            val automation = uiAutomation()
            val activeRoot = automation.rootInActiveWindow
            val windowsResult = runCatching { automation.windows }
            val windows = windowsResult.getOrNull().orEmpty()
            val packages = windows.mapNotNull { window -> window.root?.packageName?.toString() }.distinct()
            val importTabFound = appRoot()?.let { root -> findImportTabNode(root) } != null
            val windowError = windowsResult.exceptionOrNull()?.let { error -> " windowsError=${describeError(error)}" }.orEmpty()
            "windows=${windows.size} packages=$packages rootInActiveWindow=${activeRoot?.packageName} importTabFound=$importTabFound$windowError"
        }.getOrElse { error -> "observedState failed ${describeError(error)}" }

    /** The full window picture the `diagnose` mode logs: active root plus every interactive window. */
    private fun describeWindowState(): String {
        val automation = uiAutomation()
        val activeRoot = runCatching { automation.rootInActiveWindow }.getOrNull()
        val windowsResult = runCatching { automation.windows }
        val windows = windowsResult.getOrNull().orEmpty()
        val parts = mutableListOf<String>()
        parts += "rootInActiveWindow=${describeRoot(activeRoot)}"
        parts += "windows=${windows.size}"
        for ((index, window) in windows.withIndex()) {
            parts += "window[$index] type=${window.type} active=${window.isActive} focused=${window.isFocused} root=${describeRoot(window.root)}"
        }
        windowsResult.exceptionOrNull()?.let { error -> parts += "windowsError=${describeError(error)}" }
        return parts.joinToString(separator = " | ")
    }

    private fun describeRoot(node: AccessibilityNodeInfo?): String {
        if (node == null) {
            return "null"
        }
        return "pkg=${node.packageName} class=${node.className} bounds=${boundsOf(node)}"
    }

    private fun describeAutomationFlags(): String {
        val info = runCatching { uiAutomation().serviceInfo }.getOrNull()
        return "serviceInfo flags=${info?.flags}"
    }

    private fun describeError(error: Throwable): String = "${error.javaClass.simpleName}: ${error.message}"

    private fun attempt(
        label: String,
        block: () -> Unit,
    ): String {
        val error = runCatching(block).exceptionOrNull()
        return if (error == null) "$label ok" else "$label failed ${describeError(error)}"
    }

    // ---- tree helpers ----

    private fun shell(command: String) {
        val descriptor = uiAutomation().executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    private fun waitFor(
        predicate: () -> Boolean,
        timeoutMs: Int,
        pollMs: Int,
    ): Boolean {
        val deadlineNanos = System.nanoTime() + timeoutMs.toLong() * 1_000_000L
        while (System.nanoTime() < deadlineNanos) {
            if (predicate()) {
                return true
            }
            sleepQuietly(pollMs.toLong())
        }
        return predicate()
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun elapsedMillis(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000L

    private fun collectNodes(
        root: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): List<AccessibilityNodeInfo> {
        val matches = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        if (root != null) {
            queue.add(root)
        }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (predicate(node)) {
                matches += node
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { queue.add(it) }
            }
        }
        return matches
    }

    private fun boundsOf(node: AccessibilityNodeInfo): Rect {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return bounds
    }

    private fun nodeText(node: AccessibilityNodeInfo): String = node.text?.toString().orEmpty()

    private fun nodeLabel(node: AccessibilityNodeInfo): String {
        val text = nodeText(node)
        val description = node.contentDescription?.toString().orEmpty()
        if (text.isEmpty()) {
            return description
        }
        if (description.isEmpty()) {
            return text
        }
        return "$text / $description"
    }

    private fun isCandidateAmountNode(node: AccessibilityNodeInfo): Boolean {
        val text = nodeText(node)
        return text.endsWith(CURRENCY_SUFFIX)
    }

    private fun isCandidateRowNode(node: AccessibilityNodeInfo): Boolean = node.contentDescription?.toString() == CANDIDATE_CHECKBOX_DESC || isCandidateAmountNode(node)

    private fun findCandidateRow(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? = collectNodes(root) { node -> isCandidateRowNode(node) }.firstOrNull()

    private fun findByContentDescription(
        root: AccessibilityNodeInfo?,
        description: String,
    ): AccessibilityNodeInfo? {
        val matches = collectNodes(root) { node -> node.contentDescription?.toString() == description }
        return matches.firstOrNull { node -> node.isClickable } ?: matches.firstOrNull()
    }

    /** The node whose visible text is exactly [text], preferring the clickable one (a Button's merged node). */
    private fun findByText(
        root: AccessibilityNodeInfo?,
        text: String,
    ): AccessibilityNodeInfo? {
        val matches = collectNodes(root) { node -> nodeText(node) == text }
        return matches.firstOrNull { node -> node.isClickable } ?: matches.firstOrNull()
    }

    /**
     * Clicks [node], falling back to its ancestors: Compose merges a Button's text into one clickable
     * node, but a text node that did not merge refuses `ACTION_CLICK` while its clickable parent
     * accepts it. Returns whether any click was accepted.
     */
    @Suppress("DEPRECATION")
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < CLICK_PARENT_DEPTH) {
            if (parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            parent = parent.parent
            depth += 1
        }
        return false
    }

    /**
     * The label lookup that the tab resolution used to run, kept ONLY for the `importTabFound` field of
     * the [observedState] diagnostic string - it is not a resolution path any more. It matches a node
     * whose own text or contentDescription contains [IMPORT_TAB_LABEL] and resolves a clickable self or
     * ancestor within [CLICK_PARENT_DEPTH] levels; on the review screen the SELECTED tab is not
     * clickable and no ancestor is, so it returns null there, which is exactly why the second device run
     * fell through to a coordinate fallback. The review-screen dump also shows that only the 65 px wide
     * label TextView child carries 导入 and no tab node does, so this lookup can never identify the tab.
     */
    private fun findImportTabNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        val matches = collectNodes(root) { node -> labelContains(node, IMPORT_TAB_LABEL) }
        val clickable = matches.mapNotNull { node -> clickableSelfOrAncestor(node, CLICK_PARENT_DEPTH) }
        return clickable.maxByOrNull { node -> boundsOf(node).bottom }
    }

    /** Whether the node's own visible text or its content description contains [label]. */
    private fun labelContains(
        node: AccessibilityNodeInfo,
        label: String,
    ): Boolean {
        val description = node.contentDescription?.toString().orEmpty()
        return nodeText(node).contains(label) || description.contains(label)
    }

    /** [node] when it is clickable, otherwise its nearest clickable ancestor within [maxDepth] levels. */
    @Suppress("DEPRECATION")
    private fun clickableSelfOrAncestor(
        node: AccessibilityNodeInfo,
        maxDepth: Int,
    ): AccessibilityNodeInfo? {
        if (node.isClickable) {
            return node
        }
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < maxDepth) {
            if (parent.isClickable) {
                return parent
            }
            parent = parent.parent
            depth += 1
        }
        return null
    }

    private fun candidateCheckboxCount(root: AccessibilityNodeInfo?): Int = collectNodes(root) { node -> node.contentDescription?.toString() == CANDIDATE_CHECKBOX_DESC }.size

    private fun subtreeSize(node: AccessibilityNodeInfo): Int = collectNodes(node) { true }.size

    private fun containsCandidateRows(node: AccessibilityNodeInfo): Boolean = collectNodes(node) { candidate -> isCandidateRowNode(candidate) }.isNotEmpty()

    private fun scrollableNodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = collectNodes(root) { node -> node.isScrollable }

    private fun chooseScrollableContainer(containers: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        if (containers.isEmpty()) {
            return null
        }
        val withCandidateRows = containers.filter { node -> containsCandidateRows(node) }
        val pool = withCandidateRows.ifEmpty { containers }
        return pool.maxByOrNull { node -> subtreeSize(node) }
    }

    private fun selectScrollableContainer(log: (String) -> Unit): AccessibilityNodeInfo? {
        val containers = scrollableNodes(appRoot())
        log("scrollable nodes: ${containers.size}")
        for (container in containers) {
            log("scrollable ${describeNode(container)}")
        }
        val chosen = chooseScrollableContainer(containers)
        val chosenText = chosen?.let { node -> describeNode(node) } ?: "none"
        log("chosen scrollable: $chosenText")
        return chosen
    }

    /** The unprefixed log channel of the older modes: their lines go to the evidence list as they are. */
    private fun evidenceLog(evidence: MutableList<String>): (String) -> Unit = { line -> evidence += line }

    // ---- evidence rendering ----

    private fun describeNode(node: AccessibilityNodeInfo): String {
        val className = node.className
        val viewId = node.viewIdResourceName ?: "-"
        val bounds = boundsOf(node)
        val childCount = node.childCount
        val scrollable = node.isScrollable
        return "class=$className viewId=$viewId bounds=$bounds children=$childCount scrollable=$scrollable"
    }

    private fun describeNodeOrNone(node: AccessibilityNodeInfo?): String = node?.let { described -> describeNode(described) } ?: "none"

    private fun describeActions(node: AccessibilityNodeInfo): String {
        val actions = node.actionList
        val parts = mutableListOf<String>()
        for (action in actions) {
            val label = action.label ?: "-"
            parts += "id=${action.id} label=$label"
        }
        return "count=${actions.size} [${parts.joinToString(separator = "; ")}]"
    }

    private fun describeCollection(node: AccessibilityNodeInfo): String {
        val info = node.collectionInfo ?: return "none"
        return "rowCount=${info.rowCount} columnCount=${info.columnCount} hierarchical=${info.isHierarchical}"
    }

    /** The visible candidate window, split so a caller can compare the last row across checks. */
    private data class VisibleWindow(
        val amountRows: Int,
        val checkboxes: Int,
        val first: String,
        val last: String,
    )

    private fun visibleWindow(root: AccessibilityNodeInfo?): VisibleWindow {
        val texts = collectNodes(root) { node -> isCandidateAmountNode(node) }.map { node -> nodeText(node) }.filter { text -> text.isNotEmpty() }
        return VisibleWindow(
            amountRows = texts.size,
            checkboxes = candidateCheckboxCount(root),
            first = texts.firstOrNull() ?: "-",
            last = texts.lastOrNull() ?: "-",
        )
    }

    private fun describeVisibleWindow(window: VisibleWindow): String = "amountRows=${window.amountRows} checkboxes=${window.checkboxes} first=${window.first} last=${window.last}"

    private fun visibleRowWindow(root: AccessibilityNodeInfo?): String = describeVisibleWindow(visibleWindow(root))

    private fun matchesKeyPrefix(text: String): Boolean {
        val prefixes = listOf("整组", "共", "待处置", "已标记", "失败")
        for (prefix in prefixes) {
            if (text.startsWith(prefix)) {
                return true
            }
        }
        // The 整组确认页 header disclosure starts with 将 and carries the group's item count
        // ("……共 N 条。"), so that one line is matched by shape rather than by prefix.
        return text.contains(GROUP_ITEM_COUNT_PREFIX) && text.endsWith(GROUP_ITEM_COUNT_SUFFIX)
    }

    private fun keyTexts(root: AccessibilityNodeInfo?): String {
        val nodes = collectNodes(root) { node -> matchesKeyPrefix(nodeText(node)) }
        val texts = mutableListOf<String>()
        for (node in nodes) {
            val text = nodeText(node)
            if (text.isNotEmpty() && !texts.contains(text)) {
                texts += text
            }
        }
        val shown = texts.take(KEY_TEXT_LIMIT)
        val suffix = if (texts.size > KEY_TEXT_LIMIT) " (+${texts.size - KEY_TEXT_LIMIT} more)" else ""
        return "checkboxes=${candidateCheckboxCount(root)} texts=$shown$suffix"
    }

    private fun appendEvidence(lines: List<String>) {
        if (lines.isEmpty()) {
            return
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, EVIDENCE_FILE_NAME)
        file.appendText(lines.joinToString(separator = "\n", postfix = "\n"))
        for (line in lines) {
            for (chunk in line.chunked(LOGCAT_CHUNK_CHARS)) {
                Log.i(TAG, chunk)
            }
        }
    }

    // ---- instrumentation arguments ----

    private fun stringArg(
        name: String,
        fallback: String,
    ): String {
        val raw = InstrumentationRegistry.getArguments().getString(name)
        return if (raw.isNullOrBlank()) fallback else raw
    }

    private fun intArg(
        name: String,
        fallback: Int,
    ): Int {
        val raw = InstrumentationRegistry.getArguments().get(name)?.toString() ?: return fallback
        return raw.trim().toIntOrNull() ?: fallback
    }
}
