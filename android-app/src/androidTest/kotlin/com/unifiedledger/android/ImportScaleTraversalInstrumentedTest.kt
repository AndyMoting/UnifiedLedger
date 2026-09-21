package com.unifiedledger.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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
// boxes) and the 147 px wide new-expense FAB, so the raw candidates are first narrowed to
// [TAB_WIDTH_TAB_MIN] and then collapsed to one node per tab position before the index is read. The
// floor sits between the widest non-tab node in the band (the FAB, 147 px) and the narrowest tab
// (183 px), so a non-tab node can be neither a candidate nor a retry attempt.
private const val TAB_BAND_TOP = 2_000
private const val TAB_BAND_BOTTOM = 2_400
private const val TAB_WIDTH_MIN = 120
private const val TAB_WIDTH_MAX = 260
private const val TAB_WIDTH_TAB_MIN = 170
private const val IMPORT_TAB_INDEX = 3
private const val TAB_MAX_ATTEMPTS = 4
private const val ARG_TAB_LIST_WAIT_MILLIS = "tabListWaitMillis"
private const val TAB_LIST_WAIT_MILLIS = 180_000
private const val TAB_BAR_WAIT_MILLIS = 60_000
private const val CANDIDATE_CHECKBOX_DESC = "勾选候选"
private const val CURRENCY_SUFFIX = "CNY"
private const val LAST_ITEM_ROW_INDEX = 1_000_000

// ---- the import-review-screen predicate (the HOME-screen false positive fix) ----
//
// [reachImportReview] used to call a snapshot "the import review screen" as soon as it held a node
// whose text ends with [CURRENCY_SUFFIX] - the candidate amount line. The HOME screen's transaction
// lines end the same way, so the check passed 8 ms after the tab tap, on the still-rendered HOME
// screen (the device run's reading of that frame: amount rows present, checkboxes=0), the tab
// transition was never confirmed, and the scrollable-container lookup that followed found nothing.
// The predicate below matches only markers HOME does not carry: the import review header's
// [IMPORT_SCREEN_REFRESH_TEXT] / [IMPORT_SCREEN_PICK_TEXT] affordances, or the candidate row's
// [CANDIDATE_CHECKBOX_DESC] checkbox. The header items live inside the same lazy container as the
// rows, so a scrolled list has neither of them - the checkbox branch is the tolerant fallback, and it
// requires a candidate amount row in the SAME snapshot, which is what keeps a HOME-only snapshot
// (transaction lines, zero checkboxes) from satisfying it.
private const val IMPORT_SCREEN_REFRESH_TEXT = "刷新清单"
private const val IMPORT_SCREEN_PICK_TEXT = "选择账单文件"
private const val IMPORT_SCREEN_BRANCH_NONE = "none"
private const val IMPORT_SCREEN_BRANCH_REFRESH = "headerRefreshText"
private const val IMPORT_SCREEN_BRANCH_PICK = "headerPickText"
private const val IMPORT_SCREEN_BRANCH_CHECKBOX = "checkboxMarkerWithCandidateRow"
private const val IMPORT_SCREEN_WAIT_MILLIS = 30_000
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

// The picker's row walk climbs further: DocumentsUI's item title is a non-clickable TextView and the
// row does not reliably accept ACTION_CLICK within CLICK_PARENT_DEPTH levels.
private const val PICKER_CLICK_PARENT_DEPTH = 10

// Bounded scroll used by countProbe to force a LazyColumn layout pass before re-reading CollectionInfo.
private const val COUNT_PROBE_SCROLL_ACTIONS = 60
private const val DB_FILE_NAME = "ledger.db"

// ---- b5Detail (A-PERF B5 endpoint: single-candidate detail key-content latency) ----

private const val ARG_RUNS = "runs"
private const val ARG_POLL_MILLIS = "pollMillis"
private const val ARG_GATE_MILLIS = "gateMillis"
private const val ARG_ROW_INDEX = "rowIndex"
private const val DEFAULT_B5_RUNS = 3
private const val DEFAULT_B5_POLL_MILLIS = 30
private const val DEFAULT_B5_GATE_MILLIS = 1_000
private const val DEFAULT_B5_ROW_INDEX = 0
private const val B5_POLL_MILLIS_MIN = 25
private const val B5_POLL_MILLIS_MAX = 50
private const val B5_PREFIX = "b5Detail:"
private const val B5_DETAIL_TITLE = "候选详情"
private const val B5_STATUS_LINE_PREFIX = "类型 "
private const val B5_UNRESOLVED_AMOUNT = "金额未解"
private const val B5_DETAIL_UNAVAILABLE = "无法读取候选详情（本地数据库不可用）。"
private const val B5_DETAIL_ABSENT = "该候选不存在或不在当前账本。"
private const val B5_BACK_LABEL = "返回"
private const val B5_POLL_TIMEOUT_MILLIS = 30_000
private const val B5_BACK_WAIT_MILLIS = 5_000
private const val B5_LEAVE_DETAIL_WAIT_MILLIS = 15_000
private const val B5_BACK_MAX_ATTEMPTS = 3

// ---- fullChain (the whole plan section 10.3 chain in ONE instrumented session, D-162 item 4(a)) ----

private const val ARG_BATCH_ENTRY = "batchEntry"
private const val ARG_AUTHORIZE = "authorize"
private const val ARG_DETAIL_BACK = "detailBack"
private const val DEFAULT_BATCH_ENTRY = "进入批量确认"
private const val DEFAULT_AUTHORIZE = "授权逐项入账"
private const val DEFAULT_DETAIL_BACK = "返回"
private const val DEFAULT_FULL_CHAIN_WAIT_FOR_INTAKE_MILLIS = 900_000
private const val DEFAULT_FULL_CHAIN_CARD_SCROLL_ACTIONS = 4_000
private const val FULL_CHAIN_PREFIX = "fullChain:"
private const val UNSELECTED_RADIO_PREFIX = "○ "
private const val DECISION_COMPLETE_TEXT = "决策已补全。"
private const val FULL_CHAIN_DETAIL_OPEN_WAIT_MILLIS = 60_000
private const val FULL_CHAIN_DECISION_WAIT_MILLIS = 30_000
private const val FULL_CHAIN_ENTRY_WAIT_MILLIS = 30_000
private const val FULL_CHAIN_RETURN_WAIT_MILLIS = 30_000
private const val FULL_CHAIN_BATCH_SCREEN_WAIT_MILLIS = 60_000
private const val FULL_CHAIN_RADIO_MAX_ROUNDS = 12
private const val FULL_CHAIN_RADIO_SETTLE_MILLIS = 300
private const val FULL_CHAIN_KEY_TEXT_LIMIT = 40
private const val BATCH_SCREEN_TITLE = "批量确认"
private const val BATCH_ITEM_PREFIX = "候选 "
private const val BATCH_ITEM_LINE_LIMIT = 20
private const val BATCH_COMMIT_LABEL_PREFIX = "确认入账（"
private const val BATCH_COMMIT_LABEL_SUFFIX = "项）"
private const val BATCH_COMMIT_POLL_MILLIS = 2_000
private const val BATCH_COMMIT_TIMEOUT_MILLIS = 300_000
private const val IMPORT_CONFIRMATION_TABLE = "import_confirmation"
private const val POSTING_TABLE = "posting"
private const val LEDGER_TRANSACTION_TABLE = "ledger_transaction"

// ---- countProbe (the 完整计数 vector: collectionInfo.rowCount vs the authoritative DB count) ----

private const val ARG_FIXTURE_NAME = "fixtureName"
private const val ARG_FORMAT_LABEL = "formatLabel"
private const val ARG_PICK_LABEL = "pickLabel"
private const val ARG_REFRESH_LABEL = "refreshLabel"
private const val ARG_INTAKE_TIMEOUT_MILLIS = "intakeTimeoutMillis"
private const val DEFAULT_FIXTURE_NAME = "alipay-d01-dup200.csv"
private const val DEFAULT_FORMAT_LABEL = "支付宝账单（CSV）"
private const val DEFAULT_PICK_LABEL = "选择文件"
private const val DEFAULT_REFRESH_LABEL = "刷新清单"
private const val DEFAULT_COUNT_PROBE_INTAKE_TIMEOUT_MILLIS = 300_000
private const val COUNT_PROBE_PREFIX = "countProbe:"
private const val IMPORT_CANDIDATE_TABLE = "import_candidate"
private const val COUNT_PROBE_PICKER_WAIT_MILLIS = 30_000
private const val COUNT_PROBE_REFRESH_WAIT_MILLIS = 30_000
private const val COUNT_PROBE_REFRESH_SETTLE_MILLIS = 2_000

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
 * - `b5Detail`: the A-PERF B5 endpoint (frozen spec section 6: 单候选详情 = 点击首行候选 → 详情屏关键
 *   内容（金额/状态行）可见, gate ≤1s, three runs, the maximum is the reading) measured IN-PROCESS,
 *   because the host `uiautomator dump` floor (~2.29 s per dump) is far coarser than the 1 s gate and
 *   the older gfxinfo endpoint (the first `IntendedVsync` after the tap) cannot be bound to "the key
 *   content rendered". Args `runs` (default 3), `pollMillis` (default 30, clamped into the 25..50 ms
 *   window the endpoint requires), `gateMillis` (default 1000), `rowIndex` (default 0 = the first
 *   candidate row of the visible window). It taps the `rowIndex`-th candidate row with the
 *   accessibility `ACTION_CLICK` (never a synthetic gesture), polls ONE tree snapshot at a time until
 *   a snapshot holds BOTH a node whose text is exactly 候选详情 AND a node whose text is exactly the
 *   tapped row's amount line, and logs the bracket `[last absent, first present]` in ms relative to
 *   the tap plus the per-poll tree-read cost (the bound's resolution), a `summary` line and a `gate`
 *   verdict line. It never asserts the gate - the acceptance decision belongs to the host - and it
 *   excludes from the gate statistics any run whose detail screen rendered an error branch or whose
 *   endpoint never appeared, because such a run measured no latency at all. The mode must also LEAVE
 *   the detail screen between runs, and the first device run of this mode showed that the old return
 *   step could not prove it had: success was declared as soon as a candidate-row node was present, but
 *   the detail screen renders the tapped row's amount line, which is such a node - so run 1 passed the
 *   check on a stale frame while the detail was still displayed (`candidate list rendered=true
 *   elapsedMs=15`, far too fast to be a real screen transition), runs 2 and 3 then started on the detail
 *   screen, were invalidated (`detailAlreadyOpenBeforeTap`), and only 1 of the 3 required runs was
 *   valid. The return contract is now BOTH halves of ONE snapshot: no node whose text is exactly 候选详情
 *   AND at least one candidate row, awaited at the endpoint's fast poll interval and bounded by
 *   [B5_LEAVE_DETAIL_WAIT_MILLIS]; a failed wait re-locates the 返回 affordance and retries the click up
 *   to [B5_BACK_MAX_ATTEMPTS] times (`return: retry <n>/3`), re-reading the tree between attempts, and
 *   the loop proceeds only after that check passes (`return: left detail=true candidateListRendered=true
 *   elapsedMs=<n> attempts=<k>`). A detail screen that still cannot be left is a logged
 *   `FINDING: could not leave the detail screen after 3 attempts` that STOPS the mode - every further run
 *   would start on the detail screen and be invalid - and the summary/gate lines are then still emitted
 *   for the runs actually obtained.
 * - `fullChain`: the whole plan section 10.3 chain in ONE instrumented session
 *   (冷启动→首页可操作→进入导入→解析/接治→收尾重读→列表可操作→候选详情→滚动→重复组打开→审核/确认→
 *   详情与月度刷新→杀进程后重开). The 候选详情 / 滚动 pair runs in the LOW-COST order (detail first),
 *   a registered deviation from the plan's literal arrow order (D-164 item 2); every other adjacent
 *   pair keeps the plan's order. D-162 item 4 registered the gap this mode exists to close: its four
 *   stages were measured across TWO app process cycles, so the chain ORDER was not satisfied in one
 *   continuous session. Args `target` (default 整组标记为重复), `maxForwardActions` (default 30000),
 *   `settleMillis` (default 120), `stallChecks` (default 20), `cardScrollActions` (default 4000),
 *   `confirmTimeoutMillis` (default 3600000), `waitForIntakeMillis` (default 900000), `batchEntry`
 *   (default 进入批量确认), `authorize` (default 授权逐项入账) and `detailBack` (default 返回). In order:
 *   the host-driven intake phase ([awaitHostIntake], before the first automation call), [reachImportReview],
 *   the candidate detail phase ([runFullChainDetailPhase]), the traversal plus the group card open and
 *   confirmation, the batch phase ([runFullChainBatchPhase]) with the authoritative `ledger_transaction`
 *   completion wait, and the closing per-phase delta summary. Every line carries the `fullChain:` prefix.
 * - `countProbe`: the 完整计数 vector of plan section 10.3 (D-162 item 3(i), D-164 item 4(c), D-165 item
 *   4). The traversal reads the candidate list's `collectionInfo.rowCount` and it has come out SMALLER
 *   than the library's `import_candidate` count right after an in-session import, and the two readings -
 *   (A) the rendered list genuinely does not include the candidates imported in this session until
 *   something refreshes it, (B) `collectionInfo` is a stale snapshot a re-read would fix - are
 *   undistinguished. Args `fixtureName` (default `alipay-d01-dup200.csv`), `formatLabel` (default
 *   支付宝账单（CSV）), `pickLabel` (default 选择文件), `refreshLabel` (default 刷新清单), `settleMillis`
 *   (default 120) and `intakeTimeoutMillis` (default 300000). It reads `rowCount` and the authoritative
 *   `import_candidate` count at three points - baseline, right after an in-session SAF import the mode
 *   drives ITSELF (see the paragraph below), and after clicking 刷新清单 - and closes with a mechanical
 *   `interpretation` line. It asserts only the genuine preconditions (the review screen reachable, the
 *   list rendered, a scrollable container, the format label and the `选择文件` nodes); every other
 *   outcome, including an intake that never stabilizes and a picker that never appears, is a logged
 *   FINDING. Every line carries the `countProbe:` prefix.
 *
 * CHAIN-ORDER RATIONALE (why the candidate detail comes BEFORE the deep traversal): the detail screen
 * carries its OWN checkbox and its OWN 进入批量确认 entry (device dump: one `勾选候选` CheckBox, and one
 * `进入批量确认` node once the decision is complete), so the first candidate can be selected and its
 * decision completed while the list is still at the top - and the batch entry at the END of the list is
 * then reachable right after the group card, without a scroll back. The order the plan fixes is therefore
 * also the cheap one: 候选详情 first, then 滚动, then 重复组打开/审核确认, then the batch entry the same
 * session has already armed. The traversal starts from the row the detail was opened from (the list is
 * back at the top when the detail returns), so the chain's scroll phase stays a full traversal rather
 * than a scroll to the end.
 *
 * SESSION END = THE CHAIN'S KILL STEP: the mode deliberately does NOT finish the activity, and `am
 * instrument` force-stops the app process when the run ends - the plan chain's 杀进程. The host then
 * performs the reopen (`am start -W` plus the count reads) as the chain's last stage.
 *
 * Failure discipline: the only assertions are the genuine preconditions (the activity is reachable and
 * the candidate list rendered - both inside [reachImportReview] - the first candidate row exists, and the
 * traversal starts). A detail screen that cannot be LEFT stops the mode, because every later phase would
 * run against the wrong screen; every other failure - a detail that never opened, a missing decision
 * marker, an absent batch entry, a batch screen that never appeared, a batch commit that does not move
 * the `ledger_transaction` count - is a logged FINDING and the mode continues with whatever it can still
 * read, so the evidence of the phases that did run is always written.
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
 * The `countProbe` device run then failed at its FIRST precondition and exposed the fourth defect, the
 * HOME-screen false positive: [reachImportReview] read "a node whose text ends with [CURRENCY_SUFFIX]"
 * as "the import review screen is rendered", but the HOME screen's transaction lines end that way too.
 * The evidence shows the check passing 8 ms after the tab tap (`import tab attempt 1/4 rendered=true
 * elapsedMs=8`) on the still-rendered HOME frame (amount rows present, `checkboxes=0`), so the tab
 * switch was never confirmed, the scrollable-container lookup found nothing (`scrollable nodes: 0`)
 * and the mode asserted before any reading was taken. The screen half of the predicate is now
 * [readImportScreen] - exact text [IMPORT_SCREEN_REFRESH_TEXT], exact text [IMPORT_SCREEN_PICK_TEXT] or
 * contentDescription [CANDIDATE_CHECKBOX_DESC] in ONE snapshot - and the success condition of
 * [reachImportReview] is that predicate AND a candidate row, awaited as two bounded halves
 * (`import screen present=<bool> branch=<branch> elapsedMs=<n>` for [IMPORT_SCREEN_WAIT_MILLIS], then
 * `candidate list rendered: <window>` for [LIST_WAIT_MILLIS]), both of them named in the failure
 * message and in [observedState]. The checkbox branch is the tolerant half: the header items are
 * virtualized away once the list is scrolled, so a scrolled list is recognized by its rows'
 * [CANDIDATE_CHECKBOX_DESC] checkbox together with a candidate amount row in the same snapshot, while a
 * HOME-only snapshot (amount rows, zero checkboxes) satisfies neither branch. [resolveImportTab]'s
 * per-attempt wait uses the same strict predicate, so an attempt whose tap did not switch the tab no
 * longer reports `rendered=true` after a few ms and the retry loop that exists for exactly that case
 * actually runs, and `countProbe` logs the predicate's verdict again at its baseline step, before
 * `collectionInfo` is read, so a future failure at that step is unambiguous.
 *
 * The `countProbe` re-run then failed at the tab resolution itself and exposed the fifth defect: the app
 * root being present is not the tab bar being COMPOSED. That run took the launch branch, reached the root
 * 2,017 ms after `am start` (`app root wait (post-launch): timeoutMillis=30000 found=true elapsedMs=2017`)
 * and [resolveImportTab] then found `rawCandidates=0` in the bottom band, because the Compose tab bar had
 * not composed yet in that first frame - a later, longer-lived run of the same build found 23 raw
 * candidates in the same band. The empty band made the resolution take its last-resort branch (`FINDING:
 * the bottom band yielded no tab candidate`), which found no clickable node either, and the reach then
 * failed on the HOME screen's transaction lines (they end with [CURRENCY_SUFFIX], hence
 * `candidateRowSeen=true` while `importScreenPresent=false`). [reachImportReview] now awaits the tab bar
 * itself, after the app root and before the tab is resolved ([awaitTabBar], bounded by
 * [TAB_BAR_WAIT_MILLIS], every poll logged as `import tab bar wait: elapsedMs=<n> candidates=<k>`), so the
 * resolution sees a composed band; a wait that times out only logs a FINDING and the resolution still
 * runs, so the band's raw candidate count in its own resolution line remains the reading that names the
 * failure.
 *
 * The next `countProbe` run then had the composed tab bar and still failed - on two defects of the tap
 * path, both fixed here:
 * 1. The per-attempt list-render wait was a fixed [TAB_LIST_WAIT_MILLIS] (30 s), which was enough while
 *    the list had already been loaded by an in-session import (the "already on import review" early
 *    return) and is NOT enough for the FIRST full list read at this library's size: against 60,800
 *    candidates every attempt logged `rendered=false elapsedMs≈30000`. The bound is now the
 *    instrumentation argument `tabListWaitMillis` (default [TAB_LIST_WAIT_MILLIS], 180 s), read by
 *    [resolveImportTab], reported in its resolution line and in the timeout FINDING, and a SUCCESSFUL
 *    attempt logs its elapsed time too (`import tab attempt <n>/[TAB_MAX_ATTEMPTS] rendered=true
 *    elapsedMs=<n>`) - the same line shape the failing attempts already produced.
 * 2. The retry order (increasing distance from x=[IMPORT_TAB_FALLBACK_X]) reached the 新增支出 FAB -
 *    `bounds=Rect(870, 2127 - 1017, 2274) width=147`, the fifth collapsed candidate - and tapping it
 *    navigated to the new-expense screen, which is why that run ended on an empty window
 *    (`amountRows=0 checkboxes=0`). The band scan now narrows its candidates to [TAB_WIDTH_TAB_MIN]
 *    (170 px) BEFORE the collapse, so the FAB (147 px) and the tabs' 126-127 px decorations can never be
 *    a candidate - not as index [IMPORT_TAB_INDEX] and not as a retry - and every excluded node is
 *    logged with its width. A floor that leaves fewer than four candidates keeps going with what
 *    remains, so the FAB is never resurrected.
 *
 * `countProbe` is the ONE mode whose in-session import this class drives ITSELF, and the same design
 * constraint is what forces it: the host's `uiautomator dump` does not work while this instrumentation
 * holds a `UiAutomation` connection, so a host-driven import could only ever complete BEFORE the mode's
 * first automation call - which is exactly the state the 完整计数 vector already has (a pre-existing
 * library) and cannot separate reading (A) from reading (B). The mode therefore navigates the product
 * picker through the accessibility tree: it clicks the 支付宝 CSV row's 选择文件 (the `选择文件` node whose
 * vertical centre is closest to the `支付宝账单（CSV）` label's, which is what identifies that row among the
 * four format rows), waits for the system picker window (the first interactive window whose root package
 * is not [TARGET_PACKAGE] and whose subtree holds the fixture file name), clicks that row through
 * `ACTION_CLICK` - and issues ONE bounded coordinate tap at the row's centre as an explicit LAST RESORT
 * when every clickable level refuses that accessibility click (a registered deviation from the
 * accessibility-only rule, disclosed by [clickPickerRow] and reported in the evidence as
 * `accepted=false coordinateTapIssued=true`; it is one navigation tap, not the gesture storm D-161
 * registers as the INPUT-FREEZE trigger) - and then polls the authoritative `import_candidate`
 * count until the intake stabilizes. It is the only mode that depends on the host having placed the
 * fixture file where the picker shows it.
 *
 * THE `countProbe` INTERPRETATION LINE IS A LABEL FOR THE HOST'S RULING, NOT A RULING: the derivation is
 * mechanical - with `a`/`b`/`c` the baseline/post-import/post-refresh `rowCount`, `b <= a` and `c > b`
 * reads `listNotRefreshed`, `b <= a` and `c <= b` reads `collectionInfoStale`, anything else (including
 * an unreadable `rowCount`) reads `indeterminate` - and the line states `verdictBelongsToHost=true`. The
 * label is GATED on the intake (`readingGate=intakeObserved|noIntake`): when the authoritative count never
 * rose above the baseline (`intakeObserved=false`) the line reads `reading=indeterminate`, because the
 * retained evidence holds exactly such a run (`dbDelta=0 intakeObserved=false`) that still carried a
 * stale-`collectionInfo` label, so a host grepping `reading=` could read a ruling out of a run where
 * nothing was imported. The same line carries `intakeStabilized` (the intake wait's own verdict) and the
 * per-step `...ReadingSource` facts (whether a reading came from a re-selected container or is
 * `unavailable` rather than a repeat of an older handle's number). No reading of `countProbe` asserts
 * anything.
 *
 * THE POSTSCROLL STEP EMITS AN OBSERVATION, NEVER A LABEL: `postScrollCountMoved=true|false|unavailable`
 * and `postScrollRowDelta=<n|unavailable>` are derived from the `postImportRowCount` and
 * `postScrollRowCount` printed on the same line (moved = `postScrollRowCount > postImportRowCount`), so
 * they cannot be inverted by a later refactor the way the hypothesis label they replace could be, and
 * `postScrollMoveGate=ok|settleMillisNotPositive|noIntake|noScrollPerformed|rowCountUnavailable` states
 * why an `unavailable` observation is unavailable.
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

    @Test
    fun b5Detail() {
        val evidence = mutableListOf<String>()
        try {
            runB5Detail(evidence)
        } finally {
            appendEvidence(evidence)
        }
    }

    @Test
    fun fullChain() {
        val evidence = mutableListOf<String>()
        try {
            runFullChain(evidence)
        } finally {
            appendEvidence(evidence)
        }
    }

    @Test
    fun countProbe() {
        val evidence = mutableListOf<String>()
        try {
            runCountProbe(evidence)
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
     * The A-PERF B5 endpoint, measured in-process: `单候选详情 = 点击首行候选 → 详情屏关键内容（金额/状态行）
     * 可见`, gate ≤1s, three runs, the maximum is the reading (frozen spec section 6). It is measured here
     * rather than on the host because the host `uiautomator dump` floor (~2.29 s per dump) is far coarser
     * than the gate, and because the older gfxinfo endpoint (the first `IntendedVsync` after the tap)
     * cannot be bound to "the key content rendered". Every line carries the [B5_PREFIX].
     *
     * ENDPOINT PREDICATE (the same-snapshot rule): ONE tree snapshot must contain a node whose text is
     * EXACTLY [B5_DETAIL_TITLE] AND a node whose text is exactly the tapped row's amount line. The exact
     * match is what makes the title detail-only - the list row's `onClickLabel` is 查看候选详情, so a
     * substring match would accept a list frame - and the amount half binds that title to the key content
     * of the row that was tapped (amount and meta strings also appear on the list rows, so the title alone
     * and the amount alone are both ambiguous; only the pair identifies the detail screen showing THIS
     * row). The amount/status line is phase-1 content: the decision form's catalog-dependent part loads
     * asynchronously (D-148 layer 2), the amount and meta lines do not, so this predicate does not measure
     * the catalog load. The status line observed in the endpoint snapshot is logged alongside
     * (`statusLinePresent`) so the host can read the 状态行 half of the frozen endpoint too.
     *
     * WHY THE READING IS AN UPPER BOUND: a poll cannot see the frame boundary. The true render instant
     * lies between the last poll that did not show the content (`lastAbsentMs`) and the first poll that
     * did (`firstPresentMs`), so `firstPresentMs` is at or after the true latency and `lastAbsentMs` is at
     * or before it; the reported bracket is [lastAbsentMs, firstPresentMs] and the gate reads the upper
     * end. The resolution is the poll interval plus the per-poll tree-read cost, both logged
     * (`pollCostMsMedian` / `pollCostMsMax`), so a reading within a few tens of ms of the gate is readable
     * as a resolution artifact rather than as a defect. `t0` is taken immediately before the accessibility
     * click, so the interval covers input handling and the whole navigation.
     *
     * INVALID-RUN GUARD: the detail screen's error branches ([B5_DETAIL_UNAVAILABLE], [B5_DETAIL_ABSENT])
     * render no amount and no key content, and they render FAST - counted as a latency they would look like
     * a passing detail open. A run that observes one is logged as `run=<i> INVALID reason=<text>` and
     * excluded from the gate statistics (the loop continues with the next run). A run whose row carries no
     * resolved amount ([B5_UNRESOLVED_AMOUNT]), a run that starts while the detail screen is already open
     * (it would satisfy the predicate at its first poll and report a bogus ~0 ms open) and a run whose
     * endpoint never appears within [B5_POLL_TIMEOUT_MILLIS] are invalid for the same reason: no latency
     * was measured. The "the detail is already open" guard reads its OWN freshly re-read snapshot at the
     * top of each run - never the frame the previous run's return step ended on - so a detail screen that
     * the return step failed to leave is caught BEFORE the tap rather than after it.
     *
     * RETURN CONTRACT: the run loop may proceed to the next run only once the detail screen has actually
     * been left, and "left" means BOTH halves of ONE snapshot - no node whose text is exactly
     * [B5_DETAIL_TITLE] AND at least one candidate row ([readB5Return] reads them in a single tree walk so
     * the halves can never come from different frames), awaited through [waitFor] at the fast `pollMillis`
     * interval with the [B5_LEAVE_DETAIL_WAIT_MILLIS] bound. The old contract - "a candidate row is
     * present" - was satisfied by the detail screen's own amount line and passed on a stale frame, which
     * invalidated two of the first device run's three runs. A wait that does not satisfy the contract
     * re-locates and re-clicks the 返回 affordance up to [B5_BACK_MAX_ATTEMPTS] times (`return: retry
     * <n>/3`), and a detail screen that still cannot be left logs
     * `FINDING: could not leave the detail screen after 3 attempts` and STOPS the mode: every further run
     * would start on the detail screen and be invalidated, so the loop breaks and the summary/gate lines
     * are still emitted for the runs actually obtained.
     *
     * Args: `runs` (default [DEFAULT_B5_RUNS]), `pollMillis` (default [DEFAULT_B5_POLL_MILLIS], clamped
     * into the [B5_POLL_MILLIS_MIN]..[B5_POLL_MILLIS_MAX] ms window the endpoint requires), `gateMillis`
     * (default [DEFAULT_B5_GATE_MILLIS]) and `rowIndex` (default [DEFAULT_B5_ROW_INDEX]): which candidate
     * row of the visible window to tap, 0 = the first.
     *
     * The mode NEVER asserts the gate - it logs `gate gateMillis=<g> pass=<true|false>` and the acceptance
     * decision belongs to the host. It asserts only the genuine preconditions: the activity is reachable
     * and the candidate list rendered ([reachImportReview] does both) and at least one candidate row is
     * present, so a measurement that cannot be taken still leaves its evidence behind.
     */
    private fun runB5Detail(evidence: MutableList<String>) {
        val log: (String) -> Unit = { line -> evidence += "$B5_PREFIX $line" }
        val requestedPollMillis = intArg(ARG_POLL_MILLIS, DEFAULT_B5_POLL_MILLIS)
        val pollMillis = requestedPollMillis.coerceIn(B5_POLL_MILLIS_MIN, B5_POLL_MILLIS_MAX)
        val runs = intArg(ARG_RUNS, DEFAULT_B5_RUNS).coerceAtLeast(1)
        val gateMillis = intArg(ARG_GATE_MILLIS, DEFAULT_B5_GATE_MILLIS).coerceAtLeast(1)
        val rowIndex = intArg(ARG_ROW_INDEX, DEFAULT_B5_ROW_INDEX).coerceAtLeast(0)
        log("start epochMillis=${System.currentTimeMillis()}")
        log("args runs=$runs pollMillis=$pollMillis gateMillis=$gateMillis rowIndex=$rowIndex")
        if (pollMillis != requestedPollMillis) {
            log("FINDING: pollMillis=$requestedPollMillis was clamped to $pollMillis (the endpoint requires $B5_POLL_MILLIS_MIN..$B5_POLL_MILLIS_MAX ms)")
        }
        prepareAutomation()
        reachImportReview(log)
        // The library scale the reading is taken on, so the host can bind it to the collection it came
        // from. The endpoint itself needs no scrollable container, so a missing one is a logged finding
        // rather than a stop.
        val container = selectScrollableContainer(log)
        if (container == null) {
            log("FINDING: no scrollable node in the import review tree; collectionInfo.rowCount is unavailable")
        } else {
            log("list collectionInfo=${describeCollection(container)}")
        }
        val initialRows = candidateAmountRows(appRoot())
        assertTrue(
            "no candidate row (a node whose text ends with $CURRENCY_SUFFIX or reads $B5_UNRESOLVED_AMOUNT) on the import review screen; observed: ${observedState()}",
            initialRows.isNotEmpty(),
        )
        log("list window ${visibleRowWindow(appRoot())} rowCandidates=${initialRows.size}")
        var validRuns = 0
        var invalidRuns = 0
        var maxUpperMs = -1L
        for (run in 1..runs) {
            // One pre-tap snapshot per run, RE-READ here and never carried over from the previous run:
            // the row index and the "the detail is not already open" guard read the SAME frame, so `t0`
            // really is a moment at which the endpoint is absent (the bracket's lower bound depends on it).
            // The re-read is what makes the guard trustworthy after a return step: a detail screen that a
            // previous run's return step failed to leave is caught BEFORE the tap, and a run that starts on
            // the detail screen would otherwise satisfy the predicate at its first poll and report a bogus
            // ~0 ms detail open.
            val preTap = appRoot()
            val rows = candidateAmountRows(preTap)
            log("run=$run listWindow=${visibleRowWindow(preTap)} rowCandidates=${rows.size}")
            if (detailTitlePresent(preTap)) {
                invalidRuns += 1
                log("run=$run INVALID reason=detailAlreadyOpenBeforeTap")
                if (!returnToListFromDetail(run, pollMillis, log)) {
                    break
                }
                continue
            }
            val row = rows.getOrNull(rowIndex)
            if (row == null) {
                invalidRuns += 1
                log("run=$run INVALID reason=noCandidateRowAtRowIndex$rowIndex")
                continue
            }
            val rowAmount = nodeText(row)
            if (rowAmount == B5_UNRESOLVED_AMOUNT || !rowAmount.endsWith(CURRENCY_SUFFIX)) {
                invalidRuns += 1
                log("run=$run INVALID reason=noResolvedAmountAtRowIndex$rowIndex amountText=$rowAmount")
                continue
            }
            log("run=$run rowAmount=$rowAmount rowBounds=${boundsOf(row)}")
            val t0 = SystemClock.uptimeMillis()
            val t0EpochMillis = System.currentTimeMillis()
            val clicked = clickNode(row)
            log("run=$run row click accepted=$clicked t0UptimeMs=$t0 t0EpochMillis=$t0EpochMillis")
            val poll = pollB5Endpoint(t0, rowAmount, pollMillis)
            if (poll.firstPresentMs >= 0) {
                validRuns += 1
                maxUpperMs = maxOf(maxUpperMs, poll.firstPresentMs)
                log(
                    "run=$run bracketMs=[${poll.lastAbsentMs}, ${poll.firstPresentMs}] upperMs=${poll.firstPresentMs} " +
                        "polls=${poll.polls} pollCostMsMedian=${poll.costMedianMs} pollCostMsMax=${poll.costMaxMs}",
                )
                log("run=$run endpointSnapshot statusLinePresent=${poll.statusLinePresent}")
            } else {
                invalidRuns += 1
                log("run=$run INVALID reason=${poll.invalidReason}")
                log("run=$run polls=${poll.polls} pollCostMsMedian=${poll.costMedianMs} pollCostMsMax=${poll.costMaxMs}")
            }
            // The loop may proceed to the next run only once the detail screen has actually been left: a
            // run that starts on the detail screen is invalidated by the guard above, so a return step that
            // cannot prove the transition would silently cost every remaining run.
            if (!returnToListFromDetail(run, pollMillis, log)) {
                break
            }
        }
        val maxUpperText = if (maxUpperMs < 0) "unavailable" else maxUpperMs.toString()
        log("summary runs=$runs valid=$validRuns maxUpperMs=$maxUpperText invalidRuns=$invalidRuns")
        val pass = validRuns >= runs && maxUpperMs >= 0 && maxUpperMs <= gateMillis.toLong()
        log("gate gateMillis=$gateMillis pass=$pass")
        log("gate basis validRuns=$validRuns/$runs maxUpperMs=$maxUpperText pollMillis=$pollMillis rowIndex=$rowIndex")
        log("gate verdict is logged, not asserted: the acceptance decision belongs to the host")
        log("end")
    }

    /**
     * The whole plan section 10.3 chain in ONE instrumented session. The class doc carries the chain-order
     * rationale (why the candidate detail precedes the deep traversal), the session-end/kill
     * correspondence and the failure discipline; every line carries the [FULL_CHAIN_PREFIX]. The phases
     * run in the plan's order: the host-driven intake, the list, 候选详情, 滚动 + 重复组打开 + 审核/确认,
     * 详情与月度刷新 (the batch confirmation with its authoritative completion wait), and the closing
     * per-phase delta summary. The activity is deliberately NOT finished.
     */
    private fun runFullChain(evidence: MutableList<String>) {
        val log: (String) -> Unit = { line -> evidence += "$FULL_CHAIN_PREFIX $line" }
        val target = stringArg(ARG_TARGET, DEFAULT_TARGET)
        val maxForwardActions = intArg(ARG_MAX_FORWARD_ACTIONS, DEFAULT_GROUP_MAX_FORWARD_ACTIONS)
        val settleMillis = intArg(ARG_SETTLE_MILLIS, DEFAULT_GROUP_SETTLE_MILLIS)
        val stallChecks = intArg(ARG_STALL_CHECKS, DEFAULT_STALL_CHECKS)
        val cardScrollActions = intArg(ARG_CARD_SCROLL_ACTIONS, DEFAULT_FULL_CHAIN_CARD_SCROLL_ACTIONS)
        val confirmTimeoutMillis = intArg(ARG_CONFIRM_TIMEOUT_MILLIS, DEFAULT_CONFIRM_TIMEOUT_MILLIS)
        val waitForIntakeMillis = intArg(ARG_WAIT_FOR_INTAKE_MILLIS, DEFAULT_FULL_CHAIN_WAIT_FOR_INTAKE_MILLIS)
        val batchEntry = stringArg(ARG_BATCH_ENTRY, DEFAULT_BATCH_ENTRY)
        val authorize = stringArg(ARG_AUTHORIZE, DEFAULT_AUTHORIZE)
        val detailBack = stringArg(ARG_DETAIL_BACK, DEFAULT_DETAIL_BACK)
        log("start epochMillis=${System.currentTimeMillis()}")
        log(
            "args target=$target maxForwardActions=$maxForwardActions settleMillis=$settleMillis stallChecks=$stallChecks " +
                "cardScrollActions=$cardScrollActions confirmTimeoutMillis=$confirmTimeoutMillis " +
                "waitForIntakeMillis=$waitForIntakeMillis batchEntry=$batchEntry authorize=$authorize detailBack=$detailBack",
        )
        // Phase 1: the intake, before the first automation call - the host drives the product SAF import
        // with `uiautomator dump`, which a live automation connection blocks.
        val preIntake = if (waitForIntakeMillis > 0) awaitHostIntake(waitForIntakeMillis, log) else null
        // Phase 2: the list.
        prepareAutomation()
        reachImportReview(log)
        val baseline = readLedgerSnapshot(if (preIntake == null) "before" else "postIntake", log)
        if (preIntake != null) {
            logIntakeDelta(preIntake, baseline, log)
        }
        // Phase 3: 候选详情, BEFORE the traversal - the chain order D-162 item 4 registered as missing.
        if (!runFullChainDetailPhase(batchEntry, detailBack, log)) {
            log("FINDING: stopping fullChain here: the detail screen could not be left, so every later phase would run against the wrong screen")
            log("end")
            return
        }
        val afterDetail = readLedgerSnapshot("afterDetail", log)
        log("delta detail baseline=${if (preIntake == null) "before" else "post-intake"}")
        logSnapshotDelta(baseline, afterDetail, log)
        // Phase 4: 滚动 + 重复组打开 + 审核/确认.
        val container = selectScrollableContainer(log)
        assertTrue(
            "no scrollable candidate-list container on the import review screen, so the traversal cannot start; observed: ${observedState()}",
            container != null,
        )
        if (container == null) {
            log("end")
            return
        }
        log("traversal start collectionInfo=${describeCollection(container)}")
        val traversalStartNanos = System.nanoTime()
        val traversal = traverseToTarget(container, target, maxForwardActions, settleMillis, stallChecks, GROUP_REFETCH_EVERY, GROUP_PROGRESS_EVERY, log)
        val traversalElapsedMs = elapsedMillis(traversalStartNanos)
        val traversalEndRoot = appRoot()
        val targetNode = findByContentDescription(traversalEndRoot, target)
        log(
            "traversal total forwardActions=${traversal.forwardActions} elapsedMs=$traversalElapsedMs " +
                "actionsPerSecond=${ratePerSecond(traversal.forwardActions, traversalElapsedMs)} rowsPerSecond=unavailable",
        )
        log("traversal end visible window: ${visibleRowWindow(traversalEndRoot)}")
        if (targetNode == null) {
            log("FINDING: target=$target not present after ${traversal.forwardActions} forward actions; no click performed")
        } else {
            log("target node ${describeNode(targetNode)} label=${nodeLabel(targetNode)}")
            openGroupCard(targetNode, log)
            confirmGroupDisposition(confirmTimeoutMillis, cardScrollActions, settleMillis, log)
        }
        val afterGroup = readLedgerSnapshot("afterGroup", log)
        logSnapshotDelta(afterDetail, afterGroup, log)
        val auxiliaryAfterGroup = readAuxiliaryCounts("afterGroup", log)
        // Phase 5: 详情与月度刷新 - the batch entry at the end of the list, the batch screen, the
        // authorization, and the authoritative ledger_transaction completion wait.
        val batchCommit = runFullChainBatchPhase(cardScrollActions, settleMillis, batchEntry, authorize, log)
        val afterBatch = readLedgerSnapshot("afterBatch", log)
        logSnapshotDelta(afterGroup, afterBatch, log)
        val auxiliaryAfterBatch = readAuxiliaryCounts("afterBatch", log)
        // Phase 6: the close. The activity is deliberately NOT finished: the process stop that ends this
        // instrumentation session IS the plan chain's kill step, and the host performs the reopen.
        val intakeSummary = if (preIntake == null) "skipped" else "import_candidate=${countDelta(preIntake.importCandidates, baseline.importCandidates)}"
        val intakeHistorySummary = if (preIntake == null) "skipped" else countDelta(preIntake.duplicateHistoryRows, baseline.duplicateHistoryRows)
        log("summary phase=intake $intakeSummary import_duplicate_status_history=$intakeHistorySummary")
        log(
            "summary phase=detail ledger_transaction=${countDelta(baseline.ledgerTransactions, afterDetail.ledgerTransactions)} " +
                "import_candidate=${countDelta(baseline.importCandidates, afterDetail.importCandidates)} " +
                "import_duplicate_status_history=${countDelta(baseline.duplicateHistoryRows, afterDetail.duplicateHistoryRows)}",
        )
        log(
            "summary phase=group duplicate_status_history=${countDelta(afterDetail.duplicateHistoryRows, afterGroup.duplicateHistoryRows)} " +
                "CONFIRMED_DUPLICATE=${confirmedDuplicateDelta(afterDetail, afterGroup)} " +
                "ledger_transaction=${countDelta(afterDetail.ledgerTransactions, afterGroup.ledgerTransactions)}",
        )
        log(
            "summary phase=batch ledger_transaction=${countDelta(afterGroup.ledgerTransactions, afterBatch.ledgerTransactions)} " +
                "import_confirmation=${countDelta(auxiliaryAfterGroup.importConfirmations, auxiliaryAfterBatch.importConfirmations)} " +
                "posting=${countDelta(auxiliaryAfterGroup.postings, auxiliaryAfterBatch.postings)}",
        )
        log(
            "summary batchCommit committed=${batchCommit.committed} ledger_transaction=${countText(batchCommit.before)} -> " +
                "${countText(batchCommit.after)} elapsedMs=${batchCommit.elapsedMs}",
        )
        log(
            "summary chainOrder=the whole plan chain ran in this one session; the run ends without finishing the activity, " +
                "so the process stop at session end is the kill step and the host performs the reopen",
        )
        log("end")
    }

    /**
     * The 完整计数 vector of plan section 10.3 (D-162 item 3(i), D-164 item 4(c), D-165 item 4), run as
     * one bounded session: read the candidate list's `collectionInfo.rowCount` and the authoritative
     * `import_candidate` count (step 1, baseline), import the fixture file through the PRODUCT picker that
     * this mode drives itself (step 2, below), re-read both with NO refresh (step 3), click 刷新清单 and
     * re-read both (step 4), and close with the mechanical interpretation line (step 5). The activity is
     * deliberately NOT finished, like every other mode.
     *
     * The class doc carries why the mode must drive the picker itself (the host's `uiautomator dump` does
     * not work while this instrumentation holds a `UiAutomation` connection) and why the interpretation
     * line is a label for the host's ruling rather than a ruling.
     *
     * The assertions are the genuine preconditions only: the review screen is reachable and the candidate
     * list rendered ([reachImportReview]), a scrollable container exists (the vector is a reading of that
     * container's `collectionInfo`), and the format label and `选择文件` nodes exist (without them no
     * in-session import can be started). Everything after the baseline read - a picker that never appears,
     * a fixture row that cannot be clicked, an intake that never stabilizes, a refresh that cannot be
     * found, an unreadable `rowCount` - is a logged FINDING and the mode continues with whatever it can
     * still read, so the readings of the steps that did run are always written.
     *
     * ARGS ARE LOGGED BUT NOT PROVENANCE-CHECKED: [intArg] substitutes the default when a value is absent
     * or malformed, so the `args ...` line alone cannot distinguish a value the host supplied from one
     * that was defaulted (the two are the same Int by the time any step reads it). A host that needs that
     * distinction must pass every argument explicitly and bind the line to its own command. The arg layer
     * is deliberately not redesigned here.
     *
     * TWO HONESTY LIMITS ARE LOGGED AS FACTS RATHER THAN HIDDEN: the step-4 refresh detector compares the
     * VISIBLE WINDOW and new candidates are appended at the END of a 61k-item list, so on a large library
     * it can only ever produce a false negative - `refreshLandingEvidenced=false` therefore proves nothing
     * about whether the refresh landed (indeed, at this scale a ~92.5 s full-list re-read can still be in
     * flight when the postRefresh reading is taken), and `refreshSettleMillis` records the wait the step
     * actually applied. The step-4b scroll is animation-driven (D-161 item 2), so a non-positive
     * `settleMillis` is a configuration FINDING and the step-4b observation stays `unavailable`.
     */
    private fun runCountProbe(evidence: MutableList<String>) {
        val log: (String) -> Unit = { line -> evidence += "$COUNT_PROBE_PREFIX $line" }
        val fixtureName = stringArg(ARG_FIXTURE_NAME, DEFAULT_FIXTURE_NAME)
        val formatLabel = stringArg(ARG_FORMAT_LABEL, DEFAULT_FORMAT_LABEL)
        val pickLabel = stringArg(ARG_PICK_LABEL, DEFAULT_PICK_LABEL)
        val refreshLabel = stringArg(ARG_REFRESH_LABEL, DEFAULT_REFRESH_LABEL)
        val settleMillis = intArg(ARG_SETTLE_MILLIS, DEFAULT_SETTLE_MILLIS)
        val intakeTimeoutMillis = intArg(ARG_INTAKE_TIMEOUT_MILLIS, DEFAULT_COUNT_PROBE_INTAKE_TIMEOUT_MILLIS)
        log("start epochMillis=${System.currentTimeMillis()}")
        log(
            "args fixtureName=$fixtureName formatLabel=$formatLabel pickLabel=$pickLabel refreshLabel=$refreshLabel " +
                "settleMillis=$settleMillis intakeTimeoutMillis=$intakeTimeoutMillis argProvenance=unavailable",
        )
        prepareAutomation()
        reachImportReview(log)
        // The baseline's own screen check, logged BEFORE collectionInfo is read: the run that motivated
        // the import-screen predicate took this baseline on a still-rendered HOME screen, and the line
        // below makes which screen the reading came from unambiguous.
        val baselineScreen = readImportScreen(appRoot())
        log(
            "baseline import screen present=${baselineScreen.present} branch=${baselineScreen.branch} " +
                "candidateRowSeen=${baselineScreen.candidateRow} window=${visibleRowWindow(appRoot())}",
        )
        val container = selectScrollableContainer(log)
        assertTrue(
            "no scrollable candidate-list container on the import review screen, so collectionInfo.rowCount cannot be read; " +
                "observed: ${observedState()}",
            container != null,
        )
        if (container == null) {
            log("end")
            return
        }
        // Step 1: the baseline, taken before anything is imported in this session.
        val baselineRowCount = collectionRowCount(container)
        val dbBaseline = countTableRows(IMPORT_CANDIDATE_TABLE, "countProbeBaseline", log)
        log(
            "baseline rowCount=${countText(baselineRowCount)} dbCandidates=${countText(dbBaseline)} " +
                "listCandidates=${countText(baselineRowCount)} listCandidatesNote=rowCountMinusNonCandidateItemCountUnknown",
        )
        // Step 2a: the 支付宝 CSV row among the four format rows, identified as the 选择文件 node whose
        // vertical centre is closest to the format label's.
        val formatNode = findByText(appRoot(), formatLabel)
        assertTrue(
            "no node whose text is exactly $formatLabel on the import review screen, so the 支付宝 CSV row cannot be identified; " +
                "observed: ${observedState()}",
            formatNode != null,
        )
        val pickNodes = collectNodes(appRoot()) { node -> nodeText(node) == pickLabel }
        assertTrue(
            "no node whose text is exactly $pickLabel on the import review screen, so no file pick can be started; " +
                "observed: ${observedState()}",
            pickNodes.isNotEmpty(),
        )
        if (formatNode == null || pickNodes.isEmpty()) {
            log("end")
            return
        }
        val formatCenterY = boundsOf(formatNode).centerY()
        val pickNode = pickNodes.minByOrNull { node -> abs(boundsOf(node).centerY() - formatCenterY) }
        if (pickNode == null) {
            log("FINDING: none of the ${pickNodes.size} $pickLabel node(s) could be resolved to a row; the in-session import is skipped")
            log("end")
            return
        }
        val pickBounds = boundsOf(pickNode)
        log(
            "picker row: formatNode bounds=${boundsOf(formatNode)} centerY=$formatCenterY pickLabelCandidates=${pickNodes.size} " +
                "chosen bounds=$pickBounds centerY=${pickBounds.centerY()} verticalDistance=${abs(pickBounds.centerY() - formatCenterY)}",
        )
        val pickClickAccepted = clickNode(pickNode)
        log("picker row: $pickLabel click accepted=$pickClickAccepted")
        if (!pickClickAccepted) {
            log(
                "FINDING: the $pickLabel click was refused (no ACTION_CLICK accepted on the node or its ancestors within " +
                    "$CLICK_PARENT_DEPTH levels); the system picker may never open, and the picker wait below is its only evidence",
            )
        }
        sleepQuietly(settleMillis.toLong())
        // Step 2b/2c: the system picker window and the fixture row inside it. `intakeWait` stays null when
        // the picker never appeared, so the interpretation line can report the intake verdict as
        // `unavailable` rather than as a wait that ran and did not stabilize.
        val picker = awaitPickerTarget(fixtureName, log)
        var intakeWait: IntakeWaitReading? = null
        if (picker == null) {
            log(
                "FINDING: no window outside $TARGET_PACKAGE showed a node matching $fixtureName within " +
                    "$COUNT_PROBE_PICKER_WAIT_MILLIS ms; no in-session import happened and the intake wait is skipped",
            )
        } else {
            log("picker: package=${picker.packageName} matchedBy=${picker.matchedBy} node=${describeNode(picker.node)} label=${nodeLabel(picker.node)}")
            // The click's outcome is logged FAITHFULLY: `accepted` is the accessibility click's result and
            // `coordinateTapIssued` the bounded fallback's, so a row that accepted nothing cannot be read
            // as `accepted=true` (the fallback proves nothing by itself - whether the picker advanced is
            // readable only from the intake that follows).
            val pickerClick = clickPickerRow(picker.node, log)
            log("picker: fixture $fixtureName click accepted=${pickerClick.accepted} coordinateTapIssued=${pickerClick.coordinateTapIssued}")
            if (pickerClick.coordinateTapIssued) {
                log(
                    "FINDING: the fixture row accepted no ACTION_CLICK within $PICKER_CLICK_PARENT_DEPTH levels and the bounded " +
                        "coordinate tap fallback was issued instead (a registered deviation from the accessibility-only rule, see " +
                        "[clickPickerRow]); the tap is unverified and only the intake that follows can show whether it landed",
                )
            }
            sleepQuietly(settleMillis.toLong())
            // Step 2d: the authoritative intake wait, under the same stabilization rule the host-driven
            // intake phase applies (the intake commits in batches, so the first rise is not the end).
            intakeWait = awaitCountProbeIntake(dbBaseline, intakeTimeoutMillis, log)
        }
        // Step 3: right after the import, with NO refresh. The container is re-located from a fresh tree
        // read, because the list may have re-rendered while the intake was committing; a container that
        // cannot be re-selected leaves the reading `unavailable` instead of repeating a number held by a
        // handle captured before the multi-minute picker/intake interaction (see [readCollectionRowCount]).
        val postImportSelection = selectScrollableContainer(log)
        val postImportReading = readCollectionRowCount(postImportSelection, "postImport", log)
        val postImportRowCount = postImportReading.rowCount
        val dbPostImport = countTableRows(IMPORT_CANDIDATE_TABLE, "countProbePostImport", log)
        log(
            "postImport rowCount=${countText(postImportRowCount)} dbCandidates=${countText(dbPostImport)} " +
                "rowCountMinusBaseline=${diffText(postImportRowCount, baselineRowCount)} dbDelta=${diffText(dbPostImport, dbBaseline)} " +
                "readingSource=${postImportReading.source}",
        )
        // Step 4: the explicit refresh, then the same two readings. THE SUCCESS DETECTOR IS A FALSE-NEGATIVE
        // INSTRUMENT at this library's scale and is kept only as the cheap positive: it compares the VISIBLE
        // WINDOW against its pre-refresh value, while new candidates are appended at the END of a ~61k-item
        // list - the top window cannot move when the refresh lands, so a `changed=false` verdict proves
        // nothing about the landing (the same session measured a ~92.5 s full-list read, so the postRefresh
        // reading below may even be taken while the refresh's own re-read is still in flight). Both branches
        // are therefore recorded as FACTS on the interpretation line: `refreshLandingEvidenced` (the
        // detector's verdict) and `refreshSettleMillis` (the wait this step actually applied).
        var refreshSettleMillis = 0
        var refreshLandingEvidenced = false
        val refreshNode = findByText(appRoot(), refreshLabel)
        if (refreshNode == null) {
            log("FINDING: no node whose text is exactly $refreshLabel in the tree; the refresh was not triggered")
        } else {
            val windowBeforeRefresh = visibleRowWindow(appRoot())
            val refreshClickAccepted = clickNode(refreshNode)
            log("refresh: $refreshLabel node ${describeNode(refreshNode)} click accepted=$refreshClickAccepted")
            if (!refreshClickAccepted) {
                log(
                    "FINDING: the $refreshLabel click was refused, so this step triggered no refresh; the postRefresh " +
                        "reading is whatever the intake alone left in the list",
                )
            }
            sleepQuietly(settleMillis.toLong())
            refreshSettleMillis = settleMillis
            val changed = waitFor({ visibleRowWindow(appRoot()) != windowBeforeRefresh }, COUNT_PROBE_REFRESH_WAIT_MILLIS, POLL_MILLIS)
            refreshLandingEvidenced = changed
            if (changed) {
                log("refresh: the visible window changed within $COUNT_PROBE_REFRESH_WAIT_MILLIS ms")
            } else {
                log(
                    "FINDING: the visible window did not change within $COUNT_PROBE_REFRESH_WAIT_MILLIS ms; this neither proves nor " +
                        "disproves that the refresh landed (new candidates are appended at the END of the list, so the top window " +
                        "cannot move) and the step falls back to a bounded fixed wait of $COUNT_PROBE_REFRESH_SETTLE_MILLIS ms",
                )
                sleepQuietly(COUNT_PROBE_REFRESH_SETTLE_MILLIS.toLong())
                refreshSettleMillis += COUNT_PROBE_REFRESH_SETTLE_MILLIS
            }
            log("refresh: windowBefore=$windowBeforeRefresh windowAfter=${visibleRowWindow(appRoot())}")
        }
        val postRefreshSelection = selectScrollableContainer(log)
        val postRefreshReading = readCollectionRowCount(postRefreshSelection, "postRefresh", log)
        val postRefreshRowCount = postRefreshReading.rowCount
        val dbPostRefresh = countTableRows(IMPORT_CANDIDATE_TABLE, "countProbePostRefresh", log)
        log(
            "postRefresh rowCount=${countText(postRefreshRowCount)} dbCandidates=${countText(dbPostRefresh)} " +
                "rowCountMinusBaseline=${diffText(postRefreshRowCount, baselineRowCount)} readingSource=${postRefreshReading.source}",
        )
        // Step 4b: force a layout pass with a bounded scroll and re-read. A LazyColumn re-publishes its
        // CollectionInfo when it is laid out again, so a count that moves here means the earlier reading
        // was a stale CollectionInfo while the rendered list already held the new candidates; a count
        // that does not move means the rendered list genuinely lacks them. That discrimination only holds
        // when a layout pass ACTUALLY happened, so the premises are recorded rather than assumed: the
        // first refused ACTION_SCROLL_FORWARD is logged as a refusal ([logRefusal]) and leaves
        // `scrollActions=0`, the visible window is logged before and after the scroll exactly as the
        // refresh step logs it, and a `settleMillis <= 0` run cannot advance the list at all (D-161
        // item 2: the action is accepted while the list moves zero rows).
        if (settleMillis <= 0) {
            log(
                "FINDING: settleMillis=$settleMillis is not positive, so the bounded scroll cannot advance the list " +
                    "(D-161: the actuator is animation-driven and an accepted action moves zero rows without a settle); " +
                    "the moved/not-moved observation is unavailable for this run",
            )
        }
        var scrollActions = 0
        val windowBeforeScroll = visibleRowWindow(appRoot())
        val containerForScroll = refetchScrollableContainer(scrollActions, log)
        if (containerForScroll == null) {
            log(
                "FINDING: no scrollable container could be re-fetched for the bounded layout-pass scroll; no layout pass was " +
                    "forced and the step is not performed",
            )
        } else {
            while (scrollActions < COUNT_PROBE_SCROLL_ACTIONS) {
                if (!containerForScroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                    logRefusal(log, containerForScroll)
                    break
                }
                scrollActions += 1
                sleepQuietly(settleMillis.toLong())
            }
            if (scrollActions == 0) {
                log(
                    "FINDING: the first ACTION_SCROLL_FORWARD was refused, so no layout pass was forced and the postScroll " +
                        "reading cannot distinguish a stale CollectionInfo from a list that genuinely lacks the candidates",
                )
            }
        }
        log("scroll: windowBefore=$windowBeforeScroll windowAfter=${visibleRowWindow(appRoot())}")
        val postScrollSelection = refetchScrollableContainer(scrollActions, log)
        val postScrollReading = readCollectionRowCount(postScrollSelection, "postScroll", log)
        val postScrollRowCount = postScrollReading.rowCount
        val dbPostScroll = countTableRows(IMPORT_CANDIDATE_TABLE, "countProbePostScroll", log)
        log(
            "postScroll scrollActions=$scrollActions performed=${scrollActions > 0} rowCount=${countText(postScrollRowCount)} " +
                "dbCandidates=${countText(dbPostScroll)} rowCountMinusBaseline=${diffText(postScrollRowCount, baselineRowCount)} " +
                "readingSource=${postScrollReading.source}",
        )
        // Step 5: the mechanical label, GATED on the intake. The retained evidence contains a run whose
        // import never landed (`dbDelta=0 intakeObserved=false`) that still emitted
        // `reading=collectionInfoStale`, so a host grepping `reading=` could read a ruling out of a run
        // where nothing was imported; `readingGate=` now names the gate the label passed, and
        // `intakeStabilized` carries the intake wait's own verdict (class doc).
        val intakeObserved = dbBaseline != null && dbPostImport != null && dbPostImport > dbBaseline
        val readingGate = if (intakeObserved) "intakeObserved" else "noIntake"
        val reading = if (intakeObserved) countProbeReading(baselineRowCount, postImportRowCount, postRefreshRowCount) else "indeterminate"
        val intakeStabilizedText = if (intakeWait == null) "unavailable" else "${intakeWait.stabilized}"
        // The postScroll step emits an OBSERVATION, never a hypothesis name: `postScrollCountMoved` and
        // `postScrollRowDelta` are derived from the `postImportRowCount` and `postScrollRowCount` printed
        // on this same line, so no later refactor can invert them the way the replaced
        // `readingAfterScroll` label was. `postScrollMoveGate` states why an `unavailable` observation is
        // unavailable, so that token is never unexplained either.
        val postScrollObservable =
            settleMillis > 0 && intakeObserved && scrollActions > 0 && postImportRowCount != null && postScrollRowCount != null
        val postScrollMoveGate =
            when {
                postScrollObservable -> "ok"
                settleMillis <= 0 -> "settleMillisNotPositive"
                !intakeObserved -> "noIntake"
                scrollActions <= 0 -> "noScrollPerformed"
                else -> "rowCountUnavailable"
            }
        val postScrollRowDelta = if (postScrollObservable) diffText(postScrollRowCount, postImportRowCount) else "unavailable"
        val postScrollCountMoved = if (postScrollObservable) movedText(postScrollRowCount, postImportRowCount) else "unavailable"
        log(
            "interpretation baselineRowCount=${countText(baselineRowCount)} postImportRowCount=${countText(postImportRowCount)} " +
                "postRefreshRowCount=${countText(postRefreshRowCount)} postScrollRowCount=${countText(postScrollRowCount)} " +
                "dbBaseline=${countText(dbBaseline)} dbPostImport=${countText(dbPostImport)} dbPostRefresh=${countText(dbPostRefresh)} " +
                "dbPostScroll=${countText(dbPostScroll)} intakeObserved=$intakeObserved intakeStabilized=$intakeStabilizedText " +
                "readingGate=$readingGate reading=$reading " +
                "postImportReadingSource=${postImportReading.source} postRefreshReadingSource=${postRefreshReading.source} " +
                "postScrollReadingSource=${postScrollReading.source} refreshSettleMillis=$refreshSettleMillis " +
                "refreshLandingEvidenced=$refreshLandingEvidenced postScrollPerformed=${scrollActions > 0} " +
                "postScrollCountMoved=$postScrollCountMoved postScrollRowDelta=$postScrollRowDelta " +
                "postScrollMoveGate=$postScrollMoveGate verdictBelongsToHost=true",
        )
        log("end")
    }

    // ---- countProbe readings (the 完整计数 vector: collectionInfo.rowCount vs the authoritative count) ----

    /** One collectionInfo rowCount as a number, or null when the node carries no collection info at all. */
    private fun collectionRowCount(node: AccessibilityNodeInfo?): Long? = node?.collectionInfo?.rowCount?.toLong()

    /** One step's rowCount reading plus WHERE it came from, so a reading can never masquerade as fresh. */
    private data class CollectionReading(
        val rowCount: Long?,
        val source: String,
    )

    /**
     * One step's `collectionInfo.rowCount` reading. The node must have been RE-SELECTED from a tree read
     * taken for this step: a null node is reported as `unavailable` rather than read from an older handle,
     * because those handles are captured before the multi-minute picker/intake interaction and would just
     * repeat whatever count the list carried then. A node whose collection info cannot be read - it was
     * recycled, detached from the window, or answered no query - is a logged FINDING with the same
     * `unavailable` reading, never an exception: this class's contract is that a mode logs a FINDING
     * rather than failing on a reading.
     */
    private fun readCollectionRowCount(
        node: AccessibilityNodeInfo?,
        step: String,
        log: (String) -> Unit,
    ): CollectionReading {
        if (node == null) {
            log("FINDING: no scrollable container in the tree at step $step; the rowCount reading is unavailable (no handle is reused)")
            return CollectionReading(null, "unavailable")
        }
        val rowCount =
            runCatching { collectionRowCount(node) }.getOrElse { error ->
                log("FINDING: the $step rowCount read failed ${describeError(error)}; the reading is unavailable")
                return CollectionReading(null, "unavailable")
            }
        return CollectionReading(rowCount, "live")
    }

    /** A difference as one token: the value, or `unavailable` when either side of it could not be read. */
    private fun diffText(
        value: Long?,
        reference: Long?,
    ): String = if (value == null || reference == null) "unavailable" else "${value - reference}"

    /** Whether [value] moved above [reference] as one token, or `unavailable` when either side is unreadable. */
    private fun movedText(
        value: Long?,
        reference: Long?,
    ): String = if (value == null || reference == null) "unavailable" else "${value > reference}"

    /**
     * The mechanical reading label of the countProbe interpretation line, exactly as the vector defines
     * it: with `a`/`b`/`c` the baseline/post-import/post-refresh `rowCount`, `b <= a` and `c > b` reads
     * `listNotRefreshed`, `b <= a` and `c <= b` reads `collectionInfoStale`, anything else reads
     * `indeterminate`. An unreadable reading (`null`) cannot be compared and is therefore also
     * `indeterminate`. This is a LABEL FOR THE HOST'S RULING, never a ruling: the mode logs it and asserts
     * nothing about it, and its caller emits it only when the run's intake actually landed (class doc).
     *
     * The third argument is the POST-REFRESH reading of the vector's step 4 and is passed nowhere else.
     * The postScroll step of step 4b deliberately does NOT come through here - it emits the raw
     * `postScrollCountMoved` / `postScrollRowDelta` observation instead, because a hypothesis name for
     * that step was invertible by a later refactor while two numbers on one line are not.
     */
    private fun countProbeReading(
        baselineRowCount: Long?,
        postImportRowCount: Long?,
        postRefreshRowCount: Long?,
    ): String =
        when {
            baselineRowCount == null || postImportRowCount == null || postRefreshRowCount == null -> "indeterminate"
            postImportRowCount <= baselineRowCount && postRefreshRowCount > postImportRowCount -> "listNotRefreshed"
            postImportRowCount <= baselineRowCount -> "collectionInfoStale"
            else -> "indeterminate"
        }

    /** One picker window's matching node: the window's root package, the node and which attribute matched. */
    private data class PickerTarget(
        val packageName: String,
        val node: AccessibilityNodeInfo,
        val matchedBy: String,
    )

    /**
     * One [awaitCountProbeIntake] wait's outcome: `detected` (the count rose above the baseline at some
     * point during the wait) and `stabilized` (it then held still for [INTAKE_STABLE_POLLS] consecutive
     * polls). The interpretation line carries both as facts, because `intakeObserved` alone - a comparison
     * of the reads taken AFTER the wait - cannot say whether the wait itself ever saw the rise or whether
     * it timed out against a library that was still committing.
     */
    private data class IntakeWaitReading(
        val detected: Boolean,
        val stabilized: Boolean,
    )

    /**
     * Waits, bounded by [COUNT_PROBE_PICKER_WAIT_MILLIS], for the system file picker to show [fixtureName]
     * and returns the matching node. The picker is the first interactive window whose root package is NOT
     * [TARGET_PACKAGE] and whose subtree holds a node whose text contains [fixtureName] - or, when no
     * window matches by text, the first window whose subtree holds a node whose contentDescription
     * contains it. The window picture (one line per window with its root package) is logged whenever it
     * changes, so the picker's arrival is readable without one line per poll; the poll count and the
     * elapsed time are logged either way.
     */
    private fun awaitPickerTarget(
        fixtureName: String,
        log: (String) -> Unit,
    ): PickerTarget? {
        val startNanos = System.nanoTime()
        var target: PickerTarget? = null
        var poll = 0
        var lastPicture = ""
        while (target == null && elapsedMillis(startNanos) < COUNT_PROBE_PICKER_WAIT_MILLIS) {
            val windows = runCatching { uiAutomation().windows }.getOrNull().orEmpty()
            val picture = describePickerWindows(windows)
            if (picture != lastPicture) {
                lastPicture = picture
                log("picker windows poll=$poll elapsedMs=${elapsedMillis(startNanos)} $picture")
            }
            target = findPickerTarget(windows, fixtureName)
            poll += 1
            if (target == null) {
                sleepQuietly(POLL_MILLIS.toLong())
            }
        }
        log("picker wait: found=${target != null} polls=$poll elapsedMs=${elapsedMillis(startNanos)}")
        return target
    }

    /** Every interactive window's type, active/focused flags and root package, for the picker evidence. */
    private fun describePickerWindows(windows: List<AccessibilityWindowInfo>): String {
        val parts = mutableListOf("windows=${windows.size}")
        for ((index, window) in windows.withIndex()) {
            val root = runCatching { window.root }.getOrNull()
            parts += "window[$index] type=${window.type} active=${window.isActive} focused=${window.isFocused} rootPackage=${root?.packageName ?: "none"}"
        }
        return parts.joinToString(separator = " | ")
    }

    /**
     * The first window outside [TARGET_PACKAGE] whose subtree holds [fixtureName], matched on the node's
     * text first and on its contentDescription only when no window matched by text. A window whose root
     * cannot be read is skipped (the read is guarded per window, because a window can disappear between
     * the enumeration and the root read), and the app's own windows are never candidates.
     */
    private fun findPickerTarget(
        windows: List<AccessibilityWindowInfo>,
        fixtureName: String,
    ): PickerTarget? {
        var byDescription: PickerTarget? = null
        for (window in windows) {
            val root = runCatching { window.root }.getOrNull() ?: continue
            val packageName = root.packageName?.toString().orEmpty()
            if (packageName == TARGET_PACKAGE) {
                continue
            }
            val byText = collectNodes(root) { node -> nodeText(node).contains(fixtureName) }.firstOrNull()
            if (byText != null) {
                return PickerTarget(packageName, byText, "text")
            }
            if (byDescription == null) {
                val byContentDescription = collectNodes(root) { node -> contentDescriptionContains(node, fixtureName) }.firstOrNull()
                if (byContentDescription != null) {
                    byDescription = PickerTarget(packageName, byContentDescription, "contentDescription")
                }
            }
        }
        return byDescription
    }

    /** Whether a node's content description contains [text]; the picker match's fallback attribute. */
    private fun contentDescriptionContains(
        node: AccessibilityNodeInfo,
        text: String,
    ): Boolean {
        val description = node.contentDescription?.toString().orEmpty()
        return description.contains(text)
    }

    /**
     * The countProbe intake wait: polls the authoritative `import_candidate` count every
     * [INTAKE_POLL_MILLIS] until it has risen above [baseline] AND stayed unchanged for
     * [INTAKE_STABLE_POLLS] consecutive polls - the same stabilization rule [awaitHostIntake] applies,
     * because the intake commits in batches and the first rise is not the end of the import. Every poll is
     * logged. A timeout is a logged FINDING, never an assertion failure: the mode continues and reads what
     * it can. Returns the wait's own outcome ([IntakeWaitReading]) - whether the intake was ever detected,
     * whether it stabilized, and the last readable count - so the caller can carry those facts onto the
     * interpretation line instead of inferring them from the counts it reads afterwards.
     */
    private fun awaitCountProbeIntake(
        baseline: Long?,
        timeoutMillis: Int,
        log: (String) -> Unit,
    ): IntakeWaitReading {
        val startNanos = System.nanoTime()
        var reference = baseline
        var latest: Long? = baseline
        var lastObserved: Long? = null
        var stablePolls = 0
        var poll = 0
        var detected = false
        var stabilized = false
        while (!stabilized && elapsedMillis(startNanos) < timeoutMillis) {
            val count = countTableRows(IMPORT_CANDIDATE_TABLE, "countProbeIntake", log)
            poll += 1
            val elapsedMs = elapsedMillis(startNanos)
            if (count == null) {
                lastObserved = null
                stablePolls = 0
            } else {
                latest = count
                if (reference == null) {
                    reference = count
                    log("intake wait: the baseline count is unavailable; using the first readable count as the reference import_candidate=$count")
                }
                if (reference != null && count > reference) {
                    if (!detected) {
                        detected = true
                        log("intake detected dbCandidates=$count delta=${count - reference}")
                    }
                    // The streak counts consecutive above-baseline observations of the SAME count, so a
                    // later rise, a drop back to the baseline or an unreadable poll all restart it.
                    stablePolls = if (count == lastObserved) stablePolls + 1 else 0
                    lastObserved = count
                    if (stablePolls >= INTAKE_STABLE_POLLS) {
                        stabilized = true
                        log("intake stabilized dbCandidates=$count delta=${diffText(count, baseline)} elapsedMs=$elapsedMs stablePolls=$stablePolls")
                    }
                } else {
                    lastObserved = null
                    stablePolls = 0
                }
            }
            if (!stabilized) {
                log("waiting for intake: poll=$poll elapsedMs=$elapsedMs dbCandidates=${countText(count)} stablePolls=$stablePolls")
                sleepQuietly(INTAKE_POLL_MILLIS.toLong())
            }
        }
        if (!stabilized) {
            log(
                "FINDING: the intake did not stabilize (import_candidate=${countText(latest)}, detected=$detected) within " +
                    "$timeoutMillis ms; the readings after this point are taken against a library that may still be committing",
            )
        }
        return IntakeWaitReading(detected, stabilized)
    }

    /**
     * fullChain phase 3 (候选详情), run BEFORE the deep traversal (a registered deviation from the plan's
     * literal arrow order, D-164 item 2) because the detail screen carries its own checkbox and its own
     * batch entry: a candidate can be selected and its decision completed here, which is what makes the batch
     * entry at the END of the list reachable right after the group card instead of requiring a scroll back
     * to the top.
     *
     * Steps: click the FIRST candidate AMOUNT node - never the row's checkbox, whose click toggles the
     * selection instead of opening the detail - await the detail title, complete the decision by clicking
     * every unselected radio option (`○ ` prefixed; in tree order the 分类 options come first, then the
     * 资金账户 ones) and re-reading the tree after each click, await the 决策已补全。 marker, tick the
     * 勾选候选 checkbox, await the 进入批量确认 entry, then leave the detail through its 返回 affordance.
     *
     * Returns whether the flow may continue. The one stop is the mandated one - a detail screen that
     * cannot be LEFT, because every later phase would run against the wrong screen; the caller logs the
     * finding and ends the mode. A detail that never opened, a decision marker that never appeared, a
     * checkbox or entry that is absent: all logged FINDINGS, and the phase still returns true so the
     * traversal and the group evidence are still produced.
     */
    private fun runFullChainDetailPhase(
        batchEntry: String,
        detailBack: String,
        log: (String) -> Unit,
    ): Boolean {
        val startNanos = System.nanoTime()
        val rows = candidateAmountRows(appRoot())
        assertTrue(
            "no candidate amount node (a text ending with $CURRENCY_SUFFIX or reading $B5_UNRESOLVED_AMOUNT) on the import review " +
                "screen, so the detail cannot be opened; observed: ${observedState()}",
            rows.isNotEmpty(),
        )
        val row = rows.firstOrNull()
        if (row == null) {
            log("FINDING: no candidate amount node on the import review screen; the detail phase is skipped")
            return true
        }
        log("detail: first candidate amount=${nodeText(row)} bounds=${boundsOf(row)}")
        log("detail: row click accepted=${clickNode(row)}")
        val opened = waitFor({ detailTitlePresent(appRoot()) }, FULL_CHAIN_DETAIL_OPEN_WAIT_MILLIS, POLL_MILLIS)
        log("detail open=$opened elapsedMs=${elapsedMillis(startNanos)}")
        if (!opened) {
            log(
                "FINDING: $B5_DETAIL_TITLE did not appear within $FULL_CHAIN_DETAIL_OPEN_WAIT_MILLIS ms; the decision, the selection " +
                    "and the batch entry are skipped",
            )
            return true
        }
        log("detail key texts: ${detailKeyTexts(appRoot())}")
        val radioClicks = completeDetailDecision(log)
        val decisionComplete = waitFor({ findByText(appRoot(), DECISION_COMPLETE_TEXT) != null }, FULL_CHAIN_DECISION_WAIT_MILLIS, POLL_MILLIS)
        log(
            "decision: radioClicks=$radioClicks marker=$DECISION_COMPLETE_TEXT present=$decisionComplete " +
                "elapsedMs=${elapsedMillis(startNanos)}",
        )
        if (!decisionComplete) {
            log(
                "FINDING: $DECISION_COMPLETE_TEXT did not appear within $FULL_CHAIN_DECISION_WAIT_MILLIS ms after $radioClicks radio " +
                    "click(s); the batch entry may not become available",
            )
        }
        val checkbox = findByContentDescription(appRoot(), CANDIDATE_CHECKBOX_DESC)
        if (checkbox == null) {
            log("FINDING: no node with contentDescription $CANDIDATE_CHECKBOX_DESC on the detail screen; the candidate was not selected")
        } else {
            log("detail: $CANDIDATE_CHECKBOX_DESC node ${describeNode(checkbox)} click accepted=${clickNode(checkbox)}")
        }
        val entryPresent = waitFor({ findByContentDescription(appRoot(), batchEntry) != null }, FULL_CHAIN_ENTRY_WAIT_MILLIS, POLL_MILLIS)
        log("detail: $batchEntry entry present=$entryPresent elapsedMs=${elapsedMillis(startNanos)}")
        if (!entryPresent) {
            log("FINDING: the $batchEntry entry did not appear within $FULL_CHAIN_ENTRY_WAIT_MILLIS ms; the selection may not have registered")
        }
        return leaveDetailScreen(detailBack, log)
    }

    /**
     * fullChain phase 5 (详情与月度刷新): the batch confirmation at the end of the candidate list, which
     * the detail phase armed. Scrolls forward (bounded by `cardScrollActions`, through
     * [scrollToBatchEntry]) until the 进入批量确认 entry appears when it is not already in the tree, opens
     * it, logs the 批量确认 screen's item lines and its 确认入账（N 项） count text, clicks the
     * 授权逐项入账 node and waits for the authoritative commit ([awaitBatchCommit]).
     *
     * Every failure is a logged FINDING and the phase returns a reading with what it has: an absent entry,
     * a screen that never appeared and an authorization that cannot be located all leave the ledger counts
     * to the caller's snapshot reads.
     */
    private fun runFullChainBatchPhase(
        cardScrollActions: Int,
        settleMillis: Int,
        batchEntry: String,
        authorize: String,
        log: (String) -> Unit,
    ): BatchCommitReading {
        val startNanos = System.nanoTime()
        val entry = findByContentDescription(appRoot(), batchEntry) ?: scrollToBatchEntry(cardScrollActions, settleMillis, batchEntry, log)
        if (entry == null) {
            log(
                "FINDING: $batchEntry not found in the tree after up to $cardScrollActions forward actions; the batch confirmation was " +
                    "not performed",
            )
            return BatchCommitReading(committed = false, before = null, after = null, elapsedMs = elapsedMillis(startNanos))
        }
        log("batch entry node ${describeNode(entry)} label=${nodeLabel(entry)}")
        log("batch entry click accepted=${clickNode(entry)}")
        val screenOpen = waitFor({ findByText(appRoot(), BATCH_SCREEN_TITLE) != null }, FULL_CHAIN_BATCH_SCREEN_WAIT_MILLIS, POLL_MILLIS)
        log("batch screen open=$screenOpen elapsedMs=${elapsedMillis(startNanos)}")
        if (!screenOpen) {
            log("FINDING: $BATCH_SCREEN_TITLE did not appear within $FULL_CHAIN_BATCH_SCREEN_WAIT_MILLIS ms; the authorization was not performed")
            return BatchCommitReading(committed = false, before = null, after = null, elapsedMs = elapsedMillis(startNanos))
        }
        val screen = appRoot()
        log("batch screen itemLines=${batchItemLines(screen)}")
        log("batch screen commitLabel=${batchCommitLabel(screen) ?: "none"}")
        val authorizeNode = findByContentDescription(appRoot(), authorize)
        if (authorizeNode == null) {
            log("FINDING: no node with contentDescription $authorize on the $BATCH_SCREEN_TITLE screen; the authorization was not clicked")
            return BatchCommitReading(committed = false, before = null, after = null, elapsedMs = elapsedMillis(startNanos))
        }
        log("authorize node ${describeNode(authorizeNode)} label=${nodeLabel(authorizeNode)}")
        val preBatch = countTableRows(LEDGER_TRANSACTION_TABLE, "preBatch", log)
        log("batch commit baseline $LEDGER_TRANSACTION_TABLE=${countText(preBatch)}")
        log("authorize click accepted=${clickNode(authorizeNode)}")
        return awaitBatchCommit(preBatch, log)
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
     * The bounded forward scroll of fullChain phase 5, the content-description sibling of
     * [scrollToConfirmAffordance]: the batch entry 进入批量确认 sits at the END of the candidate list,
     * below the group card the previous phase left behind, so it can lie many rows beyond the viewport.
     * It uses the same scrollable-container selection and the same `ACTION_SCROLL_FORWARD` action,
     * refetches the container every [GROUP_REFETCH_EVERY] actions, logs progress every
     * [CARD_SCROLL_PROGRESS_EVERY] actions and returns as soon as the node appears. Returns null when the
     * bound is reached, the scrollable node is lost or the action is refused [CONSECUTIVE_FAILURE_LIMIT]
     * times in a row; a container that cannot be re-found is retried like the traversal's
     * ([refetchScrollableContainer]) before it is reported lost.
     */
    private fun scrollToBatchEntry(
        maxActions: Int,
        settleMillis: Int,
        description: String,
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
            if (container == null) {
                container = refetchScrollableContainer(performed, log)
            }
            val node = container
            if (node == null) {
                log("FINDING: no scrollable node while looking for $description after $performed forward actions")
                return null
            }
            val accepted = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            performed += 1
            consecutiveFailures = if (accepted) 0 else consecutiveFailures + 1
            sleepQuietly(settleMillis.toLong())
            val found = findByContentDescription(appRoot(), description)
            if (found != null) {
                log("batch entry scroll reached $description after $performed forward actions elapsedMs=${elapsedMillis(startNanos)}")
                return found
            }
            if (performed % CARD_SCROLL_PROGRESS_EVERY == 0) {
                log("batch entry scroll progress forwardActions=$performed elapsedMs=${elapsedMillis(startNanos)} ${visibleRowWindow(appRoot())}")
            }
            if (consecutiveFailures >= CONSECUTIVE_FAILURE_LIMIT) {
                log("FINDING: ACTION_SCROLL_FORWARD refused $consecutiveFailures times in a row while looking for $description after $performed forward actions")
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

    // ---- b5Detail readings (A-PERF B5 endpoint: detail key-content latency) ----

    /**
     * The candidate amount lines of the visible window, in tree order ([collectNodes] order, which is
     * breadth-first from the root, so a list's rows come out in render order). A row qualifies through
     * [isCandidateAmountNode] - the same "ends with [CURRENCY_SUFFIX]" test the traversal's row readings
     * use - or by reading exactly [B5_UNRESOLVED_AMOUNT]. An unresolved row is still a candidate row and
     * keeps its index, so `rowIndex` counts rows rather than resolved amounts, which is also what keeps
     * the invalid-run guard for such a row reachable.
     */
    private fun candidateAmountRows(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = collectNodes(root) { node -> isCandidateAmountRowNode(node) }

    /** One poll's reading of ONE snapshot: the two endpoint halves, the error branches and the status line. */
    private data class B5SnapshotReading(
        val endpointVisible: Boolean,
        val errorText: String?,
        val statusLinePresent: Boolean,
    )

    /**
     * Evaluates one snapshot in a SINGLE tree walk, so the two halves of the endpoint can never come from
     * different frames: the detail-only title [B5_DETAIL_TITLE] and the tapped row's amount must both be
     * present as EXACT node texts. The detail error branches are reported separately so the caller can
     * invalidate the run instead of counting a fast error screen as a detail open, and the presence of the
     * status line ([B5_STATUS_LINE_PREFIX]) is reported so the host can read the 状态行 half of the frozen
     * endpoint from the same snapshot.
     */
    private fun readB5Snapshot(
        root: AccessibilityNodeInfo?,
        rowAmount: String,
    ): B5SnapshotReading {
        val texts = collectNodes(root) { node -> isB5WatchedText(nodeText(node), rowAmount) }.map { node -> nodeText(node) }
        return B5SnapshotReading(
            endpointVisible = texts.contains(B5_DETAIL_TITLE) && texts.contains(rowAmount),
            errorText = texts.firstOrNull { text -> text == B5_DETAIL_UNAVAILABLE || text == B5_DETAIL_ABSENT },
            statusLinePresent = texts.any { text -> text.startsWith(B5_STATUS_LINE_PREFIX) },
        )
    }

    /** The node texts one B5 poll watches: the two endpoint halves, the two error branches, the status line. */
    private fun isB5WatchedText(
        text: String,
        rowAmount: String,
    ): Boolean =
        text == B5_DETAIL_TITLE ||
            text == rowAmount ||
            text == B5_DETAIL_UNAVAILABLE ||
            text == B5_DETAIL_ABSENT ||
            text.startsWith(B5_STATUS_LINE_PREFIX)

    /** One run's poll reading: the bracket, the poll count and the per-poll tree-read cost in ms. */
    private data class B5PollResult(
        val lastAbsentMs: Long,
        val firstPresentMs: Long,
        val polls: Int,
        val costMedianMs: Long,
        val costMaxMs: Long,
        val statusLinePresent: Boolean,
        val invalidReason: String,
    )

    /**
     * The bounded B5 poll. From `t0` it reads ONE accessibility snapshot per [pollMillis] - the read is
     * `appRoot()` plus one tree walk, and its cost is measured per poll so the resolution of the bound is
     * known - and stops at the first snapshot that satisfies the endpoint predicate or that carries a
     * detail error branch.
     *
     * `lastAbsentMs` starts at 0 and is moved forward on every poll that does not satisfy the predicate:
     * the endpoint is known absent at `t0` (the list-only window was read immediately before the tap), so
     * 0 is a true lower bound even when the very first poll already shows the detail. `firstPresentMs`
     * stays -1 until the predicate holds. A poll loop that reaches [B5_POLL_TIMEOUT_MILLIS] with neither
     * outcome returns an invalid reading whose reason is a space-free token, so the run is excluded from
     * the gate statistics instead of being counted as a (fast) PASS.
     */
    private fun pollB5Endpoint(
        t0: Long,
        rowAmount: String,
        pollMillis: Int,
    ): B5PollResult {
        val costs = mutableListOf<Long>()
        var lastAbsentMs = 0L
        var firstPresentMs = -1L
        var errorText: String? = null
        var statusLinePresent = false
        val deadline = t0 + B5_POLL_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() <= deadline) {
            val readStart = SystemClock.uptimeMillis()
            val reading = readB5Snapshot(appRoot(), rowAmount)
            costs += SystemClock.uptimeMillis() - readStart
            val atMs = SystemClock.uptimeMillis() - t0
            if (reading.endpointVisible) {
                firstPresentMs = atMs
                statusLinePresent = reading.statusLinePresent
                break
            }
            errorText = reading.errorText
            if (errorText != null) {
                break
            }
            lastAbsentMs = atMs
            sleepQuietly(pollMillis.toLong())
        }
        val invalidReason =
            when {
                errorText != null -> errorText
                firstPresentMs < 0 -> "endpointNotVisibleWithin${B5_POLL_TIMEOUT_MILLIS}ms"
                else -> ""
            }
        return B5PollResult(
            lastAbsentMs = lastAbsentMs,
            firstPresentMs = firstPresentMs,
            polls = costs.size,
            costMedianMs = medianOf(costs),
            costMaxMs = costs.maxOrNull() ?: 0L,
            statusLinePresent = statusLinePresent,
            invalidReason = invalidReason,
        )
    }

    /** The median of [values] in ms (0 for an empty list); an even count takes the lower of the two middles. */
    private fun medianOf(values: List<Long>): Long {
        if (values.isEmpty()) {
            return 0L
        }
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    /**
     * Whether ONE snapshot shows the detail screen's title ([B5_DETAIL_TITLE] as an EXACT node text). The
     * exact match is what makes the title detail-only - the list row's `onClickLabel` is 查看候选详情, so a
     * substring match would accept a list frame - and both the pre-tap invalid-run guard and the return
     * contract read this half.
     */
    private fun detailTitlePresent(root: AccessibilityNodeInfo?): Boolean = root != null && findByText(root, B5_DETAIL_TITLE) != null

    /**
     * One snapshot's reading of the return contract: the detail title gone AND a candidate row present,
     * both taken from the SAME frame.
     */
    private data class B5ReturnReading(
        val detailTitlePresent: Boolean,
        val candidateRowPresent: Boolean,
    ) {
        /** Whether this snapshot shows the candidate list rather than the detail screen. */
        val leftDetail: Boolean get() = !detailTitlePresent && candidateRowPresent
    }

    /**
     * Reads the return contract from ONE tree walk, so its two halves can never come from different
     * frames - the same-snapshot rule the endpoint predicate follows. The candidate-row half uses
     * [isCandidateRowNode], the class's candidate-row test, so "the list is back" means the same thing
     * here as it does in the other modes' row readings.
     */
    private fun readB5Return(root: AccessibilityNodeInfo?): B5ReturnReading {
        val nodes = collectNodes(root) { node -> nodeText(node) == B5_DETAIL_TITLE || isCandidateRowNode(node) }
        return B5ReturnReading(
            detailTitlePresent = nodes.any { node -> nodeText(node) == B5_DETAIL_TITLE },
            candidateRowPresent = nodes.any { node -> isCandidateRowNode(node) },
        )
    }

    /**
     * Returns to the candidate list after one run and reports whether the run loop may proceed.
     *
     * THE DEFECT THIS CONTRACT FIXES (first device run of this mode): the old return step declared success
     * as soon as a candidate-row node was present, but the detail screen renders the tapped row's amount
     * line, which IS such a node - so the check passed on a stale frame while the detail was still
     * displayed (run 1 logged `candidate list rendered=true elapsedMs=15`, far too fast to be a real screen
     * transition), runs 2 and 3 then started on the detail screen, were invalidated by
     * `detailAlreadyOpenBeforeTap`, and only 1 of the 3 required runs was valid. Success is now BOTH halves
     * of ONE snapshot: NO node whose text is exactly [B5_DETAIL_TITLE] AND at least one candidate row, read
     * by [readB5Return] in a single tree walk.
     *
     * Each attempt re-reads the tree and RE-LOCATES the 返回 affordance ([clickBackAffordance]); between
     * attempts nothing is kept from the previous attempt, so a retry can never click a node the tree no
     * longer holds. The leave-detail wait is [waitFor] over [readB5Return] at the endpoint's fast poll
     * interval (`pollMillis`), bounded by [B5_LEAVE_DETAIL_WAIT_MILLIS]; a wait that does not satisfy the
     * contract consumes one of [B5_BACK_MAX_ATTEMPTS] click attempts, each retry logged as
     * `return: retry <n>/3`. Every outcome logs `return: left detail=<b> candidateListRendered=<b>
     * elapsedMs=<n> attempts=<k>`.
     *
     * A detail screen that still cannot be left is a logged
     * `FINDING: could not leave the detail screen after 3 attempts` and returns false: the caller STOPS the
     * mode, because every further run would start on the detail screen and be invalidated, and the
     * summary/gate lines are then still emitted for the runs actually obtained.
     */
    private fun returnToListFromDetail(
        run: Int,
        pollMillis: Int,
        log: (String) -> Unit,
    ): Boolean {
        val startNanos = System.nanoTime()
        var attempts = 0
        var left = false
        var reading = readB5Return(appRoot())
        while (!left && attempts < B5_BACK_MAX_ATTEMPTS) {
            attempts += 1
            clickBackAffordance(run, attempts, pollMillis, log)
            left =
                waitFor(
                    {
                        reading = readB5Return(appRoot())
                        reading.leftDetail
                    },
                    B5_LEAVE_DETAIL_WAIT_MILLIS,
                    pollMillis,
                )
        }
        log(
            "run=$run return: left detail=$left candidateListRendered=${reading.candidateRowPresent} " +
                "elapsedMs=${elapsedMillis(startNanos)} attempts=$attempts",
        )
        if (!left) {
            log("FINDING: could not leave the detail screen after $B5_BACK_MAX_ATTEMPTS attempts")
            log("FINDING: stopping b5Detail: the detail screen is still open, so every remaining run would start on it and be invalid")
        }
        return left
    }

    /**
     * One attempt at leaving the detail screen through its 返回 affordance, which is re-located on every
     * attempt: the tree is read HERE, the affordance is resolved from that read, and the click goes through
     * [clickNode] - the affordance's own text node is not clickable, so it is the ancestor walk that gets
     * the click accepted. The affordance is clicked only while the detail title is present in the same
     * snapshot: on the candidate list there is nothing to leave, and a 返回 that belongs to another screen
     * would navigate away from the measurement. A 返回 affordance that is absent while the detail IS open
     * is a logged finding; the attempt is still consumed, so the caller's bounded leave-detail wait runs
     * anyway.
     */
    private fun clickBackAffordance(
        run: Int,
        attempt: Int,
        pollMillis: Int,
        log: (String) -> Unit,
    ) {
        // Every attempt but the first is a retry and says so, so the evidence shows how often it took.
        val prefix = if (attempt == 1) "" else "retry $attempt/$B5_BACK_MAX_ATTEMPTS "
        val root = appRoot()
        if (root != null && !detailTitlePresent(root)) {
            log("run=$run return: ${prefix}the detail screen is not open; no $B5_BACK_LABEL click")
            return
        }
        var back = findByText(root, B5_BACK_LABEL)
        if (back == null) {
            // Fall back to a bounded wait and re-check: the detail screen may still be composing when the
            // endpoint poll ends.
            val backReady = waitFor({ findByText(appRoot(), B5_BACK_LABEL) != null }, B5_BACK_WAIT_MILLIS, pollMillis)
            back = if (backReady) findByText(appRoot(), B5_BACK_LABEL) else null
        }
        if (back == null) {
            log("run=$run return: ${prefix}no $B5_BACK_LABEL affordance within $B5_BACK_WAIT_MILLIS ms; the detail screen may not be open")
        } else {
            log("run=$run return: ${prefix}$B5_BACK_LABEL click accepted=${clickNode(back)} node=${describeNode(back)}")
        }
    }

    // ---- fullChain readings (detail screen, batch entry, batch commit) ----

    /**
     * Clicks the detail screen's UNSELECTED radio options until none is left, re-reading the tree after
     * every click: a click replaces one `○ ` option with its selected `● ` form, and the next option (the
     * 资金账户 group after the 分类 group, in tree order) then becomes the first remaining `○ ` node. The
     * loop is bounded by [FULL_CHAIN_RADIO_MAX_ROUNDS]; a bound reached with options still unselected is a
     * logged finding. Returns the number of clicks performed.
     */
    private fun completeDetailDecision(log: (String) -> Unit): Int {
        var clicks = 0
        var round = 0
        while (round < FULL_CHAIN_RADIO_MAX_ROUNDS) {
            round += 1
            val radio = collectNodes(appRoot()) { node -> nodeText(node).startsWith(UNSELECTED_RADIO_PREFIX) }.firstOrNull() ?: break
            log("decision: round=$round option=${nodeText(radio)} click accepted=${clickNode(radio)}")
            clicks += 1
            sleepQuietly(FULL_CHAIN_RADIO_SETTLE_MILLIS.toLong())
        }
        val remaining = collectNodes(appRoot()) { node -> nodeText(node).startsWith(UNSELECTED_RADIO_PREFIX) }.size
        if (remaining > 0) {
            log("FINDING: $remaining unselected radio option(s) still present after $clicks click(s) in $round round(s)")
        }
        return clicks
    }

    /** The detail screen's visible texts, in tree order and deduplicated, capped for the evidence file. */
    private fun detailKeyTexts(root: AccessibilityNodeInfo?): String {
        val texts = collectNodes(root) { node -> nodeText(node).isNotEmpty() }.map { node -> nodeText(node) }.distinct()
        val shown = texts.take(FULL_CHAIN_KEY_TEXT_LIMIT)
        val suffix = if (texts.size > FULL_CHAIN_KEY_TEXT_LIMIT) " (+${texts.size - FULL_CHAIN_KEY_TEXT_LIMIT} more)" else ""
        return "count=${texts.size} texts=$shown$suffix"
    }

    /**
     * Leaves the detail screen through its 返回 affordance and PROVES it, with the same both-halves
     * contract `b5Detail` uses: ONE snapshot with NO node whose text is exactly [B5_DETAIL_TITLE] AND at
     * least one candidate row ([readB5Return]). The affordance is re-located from a fresh tree read on
     * every attempt, the click goes through [clickNode] (the 返回 text node is not clickable, so the
     * ancestor walk is what gets the click accepted), the leave-detail wait is
     * [FULL_CHAIN_RETURN_WAIT_MILLIS] per attempt and the attempts are bounded by [B5_BACK_MAX_ATTEMPTS].
     * Returns false only when the detail screen could not be left - the one failure that stops fullChain.
     */
    private fun leaveDetailScreen(
        detailBack: String,
        log: (String) -> Unit,
    ): Boolean {
        val startNanos = System.nanoTime()
        var attempts = 0
        var left = false
        var reading = readB5Return(appRoot())
        while (!left && attempts < B5_BACK_MAX_ATTEMPTS) {
            attempts += 1
            val root = appRoot()
            val back = if (root != null && detailTitlePresent(root)) findByText(root, detailBack) else null
            if (back == null) {
                log("detail return: attempt=$attempts no $detailBack affordance in a snapshot showing $B5_DETAIL_TITLE")
            } else {
                log("detail return: attempt=$attempts $detailBack click accepted=${clickNode(back)} node=${describeNode(back)}")
            }
            left =
                waitFor(
                    {
                        reading = readB5Return(appRoot())
                        reading.leftDetail
                    },
                    FULL_CHAIN_RETURN_WAIT_MILLIS,
                    POLL_MILLIS,
                )
        }
        log(
            "detail return: left=$left candidateListRendered=${reading.candidateRowPresent} attempts=$attempts " +
                "elapsedMs=${elapsedMillis(startNanos)}",
        )
        if (!left) {
            log("FINDING: could not leave the detail screen after $B5_BACK_MAX_ATTEMPTS attempts")
        }
        return left
    }

    /**
     * The batch screen's item lines: the rows it lists for the selected candidates, taken by shape
     * ([BATCH_ITEM_PREFIX] `候选 `, or an amount line ending with [CURRENCY_SUFFIX], or
     * [B5_UNRESOLVED_AMOUNT]), deduplicated and capped at [BATCH_ITEM_LINE_LIMIT] for the evidence file.
     * The device dump of this build renders one line per selected candidate as `候选 <id>：<amount> CNY`.
     */
    private fun batchItemLines(root: AccessibilityNodeInfo?): String {
        val texts = collectNodes(root) { node -> isBatchItemLine(node) }.map { node -> nodeText(node) }.distinct()
        val shown = texts.take(BATCH_ITEM_LINE_LIMIT)
        val suffix = if (texts.size > BATCH_ITEM_LINE_LIMIT) " (+${texts.size - BATCH_ITEM_LINE_LIMIT} more)" else ""
        return "count=${texts.size} texts=$shown$suffix"
    }

    private fun isBatchItemLine(node: AccessibilityNodeInfo): Boolean {
        val text = nodeText(node)
        return text.startsWith(BATCH_ITEM_PREFIX) || isCandidateAmountNode(node) || text == B5_UNRESOLVED_AMOUNT
    }

    /** The batch screen's commit label (`确认入账（N 项）`): the number of items the authorization will commit. */
    private fun batchCommitLabel(root: AccessibilityNodeInfo?): String? =
        collectNodes(root) { node ->
            nodeText(node).startsWith(BATCH_COMMIT_LABEL_PREFIX) && nodeText(node).endsWith(BATCH_COMMIT_LABEL_SUFFIX)
        }.firstOrNull()?.let { node -> nodeText(node) }

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
     * One table's authoritative count through a fresh read-only open, for the readings that must not wait
     * for a full [LedgerSnapshot]: the batch-commit poll reads [LEDGER_TRANSACTION_TABLE] every
     * [BATCH_COMMIT_POLL_MILLIS]. Same failure contract as every other read - a failed open or query is a
     * logged finding and a null reading, never a test failure.
     */
    private fun countTableRows(
        table: String,
        label: String,
        log: (String) -> Unit,
    ): Long? {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = context.getDatabasePath(DB_FILE_NAME)
        return runCatching {
            SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                countRows(db, table, label, log)
            }
        }.getOrElse { error ->
            log("FINDING: db $label open failed for count($table) ${describeError(error)}")
            null
        }
    }

    /**
     * The formal-effect counts a phase summary needs beyond [LedgerSnapshot]: [IMPORT_CONFIRMATION_TABLE]
     * (one row per authorized batch item) and [POSTING_TABLE] (the ledger legs). One read-only open, the
     * same failure contract as every other reading.
     */
    private data class AuxiliaryCounts(
        val importConfirmations: Long?,
        val postings: Long?,
    )

    private fun readAuxiliaryCounts(
        label: String,
        log: (String) -> Unit,
    ): AuxiliaryCounts {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = context.getDatabasePath(DB_FILE_NAME)
        val database =
            runCatching { SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READONLY) }
                .getOrElse { error ->
                    log("FINDING: db $label open failed ${describeError(error)}; auxiliary counts unavailable, continuing")
                    return AuxiliaryCounts(null, null)
                }
        return database.use { db ->
            val counts =
                AuxiliaryCounts(
                    importConfirmations = countRows(db, IMPORT_CONFIRMATION_TABLE, label, log),
                    postings = countRows(db, POSTING_TABLE, label, log),
                )
            log("db $label $IMPORT_CONFIRMATION_TABLE=${countText(counts.importConfirmations)} $POSTING_TABLE=${countText(counts.postings)}")
            counts
        }
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

    /** A count reading as one token: the value, or `unavailable` when the read failed. */
    private fun countText(value: Long?): String = value?.toString() ?: "unavailable"

    /** The CONFIRMED_DUPLICATE histogram movement between two snapshots (the 正式效果次数 reading). */
    private fun confirmedDuplicateDelta(
        before: LedgerSnapshot,
        after: LedgerSnapshot,
    ): Long =
        histogramCount(after.duplicateStatusHistogram, CONFIRMED_DUPLICATE_STATUS) -
            histogramCount(before.duplicateStatusHistogram, CONFIRMED_DUPLICATE_STATUS)

    /** One batch-commit reading: whether the authoritative count rose, and the before/after it rose between. */
    private data class BatchCommitReading(
        val committed: Boolean,
        val before: Long?,
        val after: Long?,
        val elapsedMs: Long,
    )

    /**
     * fullChain phase 5d, the AUTHORITATIVE completion wait for the batch confirmation: the UI's own
     * progress copy is not the completion criterion, the formal effect is. It polls
     * [LEDGER_TRANSACTION_TABLE] every [BATCH_COMMIT_POLL_MILLIS] through the same read-only count the
     * snapshot reads use, logs every poll (so the host reads the commit curve), and succeeds as soon as
     * the count rises above the pre-authorization value, logging
     * `batch commit ledger_transaction <before> -> <after> elapsedMs=<n>`. When the bound is reached
     * without a rise, the same reading is logged as a FINDING with its elapsed time, so a commit that
     * never happened is never silently reported as one that did.
     */
    private fun awaitBatchCommit(
        before: Long?,
        log: (String) -> Unit,
    ): BatchCommitReading {
        val startNanos = System.nanoTime()
        var latest: Long? = before
        var elapsedMs = 0L
        while (elapsedMs < BATCH_COMMIT_TIMEOUT_MILLIS) {
            val count = countTableRows(LEDGER_TRANSACTION_TABLE, "batchCommit", log)
            if (count != null) {
                latest = count
            }
            elapsedMs = elapsedMillis(startNanos)
            log("batch commit progress elapsedMs=$elapsedMs $LEDGER_TRANSACTION_TABLE=${countText(count)}")
            if (before != null && count != null && count > before) {
                log("batch commit $LEDGER_TRANSACTION_TABLE $before -> $count elapsedMs=$elapsedMs")
                return BatchCommitReading(committed = true, before = before, after = count, elapsedMs = elapsedMs)
            }
            sleepQuietly(BATCH_COMMIT_POLL_MILLIS.toLong())
        }
        log(
            "FINDING: batch commit $LEDGER_TRANSACTION_TABLE ${countText(before)} -> ${countText(latest)} elapsedMs=$elapsedMs " +
                "(no increase within $BATCH_COMMIT_TIMEOUT_MILLIS ms)",
        )
        return BatchCommitReading(committed = false, before = before, after = latest, elapsedMs = elapsedMs)
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
     * 4. Only then is the bottom band itself awaited ([awaitTabBar], bounded by [TAB_BAR_WAIT_MILLIS]) -
     *    the app root appearing is not the tab bar being composed, and the `countProbe` re-run resolved
     *    the tab against a frame that had not drawn the bar yet (`rawCandidates=0` about 2 s after the
     *    launch) - and only after that is the import tab resolved ([resolveImportTab], by tab-bar
     *    geometry) and the candidate list awaited.
     * Every wait logs its outcome, so the host can read which branch the run took.
     *
     * SUCCESS CONDITION = BOTH HALVES: the snapshot must be the import review screen by its OWN markers
     * ([readImportScreen]) AND hold a candidate row. The screen half used to be "a node whose text ends
     * with [CURRENCY_SUFFIX]", which the HOME screen's transaction lines satisfy - so a check taken
     * before the tab transition landed passed on HOME (the device run: `rendered=true elapsedMs=8`,
     * `checkboxes=0`) and the scrollable-container lookup that followed found nothing. The screen half is
     * awaited first ([awaitImportScreen], bounded by [IMPORT_SCREEN_WAIT_MILLIS]) and the row half after
     * it ([LIST_WAIT_MILLIS]); each logs its own line, and both halves are carried in the failure message
     * and in [observedState].
     */
    private fun reachImportReview(log: (String) -> Unit) {
        val rootPresent = awaitAppRoot(APP_ROOT_WAIT_MILLIS, "pre-decision", log)
        if (rootPresent && alreadyOnImportReview("already on import review", log)) {
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
        if (alreadyOnImportReview("already on import review after the app root wait", log)) {
            return
        }
        // The settle the launch branch needs before the tab is resolved: the app root can appear seconds
        // before the Compose tab bar is composed, and the resolution against such a frame read an empty
        // band (class doc, the fifth defect). The wait is bounded and its timeout only logs a FINDING, so
        // the resolution below still runs and still reports the band it saw.
        awaitTabBar(log)
        val resolved = resolveImportTab(log)
        val screenPresent = awaitImportScreen(log)
        val rowWaitStartNanos = System.nanoTime()
        val rendered = screenPresent && waitFor({ importReviewRendered(appRoot()) }, LIST_WAIT_MILLIS, POLL_MILLIS)
        if (rendered) {
            log("candidate list rendered: ${visibleRowWindow(appRoot())}")
            return
        }
        // The failure names BOTH halves: the screen predicate's verdict and its branch, and whether a
        // candidate row was seen at all - so the half that was missing is unambiguous in the evidence.
        val reading = readImportScreen(appRoot())
        log(
            "candidate list rendered=false elapsedMs=${elapsedMillis(rowWaitStartNanos)} " +
                "importScreenPresent=$screenPresent branch=${reading.branch} candidateRowSeen=${reading.candidateRow} " +
                "window=${visibleRowWindow(appRoot())}",
        )
        assertTrue(
            "import candidate list did not render within $LIST_WAIT_MILLIS ms (import screen present=$screenPresent, " +
                "branch=${reading.branch}, candidate row seen=${reading.candidateRow}, tabResolved=$resolved; the import " +
                "review screen is identified by exact text $IMPORT_SCREEN_REFRESH_TEXT, exact text $IMPORT_SCREEN_PICK_TEXT " +
                "or contentDescription $CANDIDATE_CHECKBOX_DESC); observed: ${observedState()}",
            rendered,
        )
    }

    /**
     * The strict "the import review screen is on screen AND it holds a candidate row" test of ONE snapshot:
     * the success condition of [reachImportReview] and of every tab-resolution attempt. Both halves come
     * from the same snapshot, so a frame taken before the tab transition landed cannot pass ([readImportScreen]
     * carries the markers).
     */
    private fun importReviewRendered(root: AccessibilityNodeInfo?): Boolean {
        val reading = readImportScreen(root)
        return reading.present && reading.candidateRow
    }

    /**
     * The early-return branch of [reachImportReview]: returns true, and logs the branch that matched plus
     * the visible window, when ONE snapshot is already the import review screen with a candidate row; a
     * snapshot that is not (the HOME screen included) returns false and the caller continues down the
     * tab-resolution path.
     */
    private fun alreadyOnImportReview(
        label: String,
        log: (String) -> Unit,
    ): Boolean {
        val reading = readImportScreen(appRoot())
        if (!reading.present || !reading.candidateRow) {
            return false
        }
        log("$label: ${visibleRowWindow(appRoot())} importScreenBranch=${reading.branch}")
        return true
    }

    /**
     * Waits, bounded by [IMPORT_SCREEN_WAIT_MILLIS], for ONE snapshot to be the import review screen, and
     * logs the verdict on its own line: `import screen present=<bool> branch=<branch> elapsedMs=<n>`. The
     * wait polls, so a snapshot taken before the tab transition landed - the HOME-screen false positive
     * this predicate exists for - does not satisfy it, and the row half is awaited only after this half
     * held (a failure therefore names the half that was missing).
     */
    private fun awaitImportScreen(log: (String) -> Unit): Boolean {
        val startNanos = System.nanoTime()
        var reading = readImportScreen(appRoot())
        while (!reading.present && elapsedMillis(startNanos) < IMPORT_SCREEN_WAIT_MILLIS) {
            sleepQuietly(POLL_MILLIS.toLong())
            reading = readImportScreen(appRoot())
        }
        log("import screen present=${reading.present} branch=${reading.branch} elapsedMs=${elapsedMillis(startNanos)}")
        return reading.present
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
     * Waits, bounded by [TAB_BAR_WAIT_MILLIS], for the bottom band to hold at least one node wide enough
     * to be a tab ([bottomBandTabNodes], so the count excludes the new-expense FAB and the tabs'
     * decorations), and returns the count it ended on. The app root appearing is not the tab bar being COMPOSED: the
     * `countProbe` re-run reached the root 2,017 ms after `am start` (`app root wait (post-launch):
     * found=true elapsedMs=2017`) and [resolveImportTab] then read `rawCandidates=0` from that first frame,
     * because the Compose tab bar had not composed yet - a later, longer-lived run of the same build found
     * 23 raw candidates in the same band. Without this wait the resolution falls straight through to its
     * last-resort branch on a frame that simply had not drawn the bar. Every poll logs its elapsed time and
     * the count it saw (`import tab bar wait: elapsedMs=<n> candidates=<k>`); a band that is still empty at
     * the bound is a logged FINDING, and the caller then continues into [resolveImportTab] unchanged - the
     * raw candidate count in that function's own resolution line stays the reading a failure is read from.
     */
    private fun awaitTabBar(log: (String) -> Unit): Int {
        val startNanos = System.nanoTime()
        var candidates: Int
        do {
            candidates = bottomBandTabNodes(appRoot()).size
            log("import tab bar wait: elapsedMs=${elapsedMillis(startNanos)} candidates=$candidates")
            if (candidates > 0) {
                break
            }
            sleepQuietly(POLL_MILLIS.toLong())
        } while (elapsedMillis(startNanos) < TAB_BAR_WAIT_MILLIS)
        if (candidates == 0) {
            log(
                "FINDING: the bottom band held no tab candidate within $TAB_BAR_WAIT_MILLIS ms; " +
                    "the tab resolution continues with an empty band",
            )
        }
        return candidates
    }

    /**
     * The import tab, resolved by tab-bar geometry (Fix B) instead of the dead label lookup - the
     * tab-bar nodes expose no text and no contentDescription on this build, so a lookup can never
     * succeed - and instead of the nearest-clickable coordinate fallback, which picked the 3rd tab
     * (centre x=532) rather than the import tab and switched the app away from the import screen.
     *
     * The bottom band ([TAB_BAND_TOP]..[TAB_BAND_BOTTOM]) is scanned ONCE ([scanBottomBand]) for nodes
     * between [TAB_WIDTH_MIN] and [TAB_WIDTH_MAX] wide, regardless of clickability (the selected tab is
     * not clickable), sorted by their left edge; every raw candidate and every node the [TAB_WIDTH_TAB_MIN]
     * tab width floor EXCLUDED is logged with its index, bounds, centre, width and clickable flag, and the
     * resolution line reports the raw and the tab candidate counts plus the wait bound it will use. Only
     * the nodes the floor keeps are then collapsed to one node per tab position ([collapseTabCandidates]),
     * so the new-expense FAB (147 px, tapped as a retry by the run behind class doc defect 2) cannot enter
     * the candidate list at all. The import tab is bottom-band index [IMPORT_TAB_INDEX] (the 4th tab,
     * centre x=[IMPORT_TAB_FALLBACK_X] on the managed AVD's 1080x2400 profile):
     * - a non-clickable import tab IS the already-selected tab: nothing is tapped at all;
     * - a clickable one is clicked and the import review screen WITH a candidate row is awaited for
     *   `tabListWaitMillis` ms (default [TAB_LIST_WAIT_MILLIS], 180 s, because the first full list read at
     *   this library's size does not finish inside the 30 s this used to allow)
     *   ([importReviewRendered], the strict both-halves test - the loose "a node's text ends with CNY"
     *   test is satisfied by the HOME screen's transaction lines and made every attempt report
     *   `rendered=true` after a few ms), and the attempt's elapsed time is logged whether it rendered or
     *   not;
     * - when the screen does not render, the remaining tab candidates are retried in increasing distance
     *   from x=[IMPORT_TAB_FALLBACK_X], bounded to [TAB_MAX_ATTEMPTS] taps in total, every attempt and its
     *   outcome logged, and the resolution stops as soon as the screen renders. Fewer than four
     *   candidates (the floor dropped some, or the band is thin) is not an error: the resolution keeps
     *   going with what remains and never resurrects an excluded node.
     * A bottom band with no node at or above the floor is the only case that still falls back to the
     * nearest clickable node to ([IMPORT_TAB_FALLBACK_X], [IMPORT_TAB_FALLBACK_Y]), and it says so in the
     * evidence. The caller awaits the band itself before calling this ([awaitTabBar]), so an empty band
     * here means the tab bar did not compose within that bound - not that the frame was read too early.
     * Returns whether the candidate list rendered during the resolution.
     */
    private fun resolveImportTab(log: (String) -> Unit): Boolean {
        val listWaitMillis = intArg(ARG_TAB_LIST_WAIT_MILLIS, TAB_LIST_WAIT_MILLIS)
        val scan = scanBottomBand(appRoot())
        log(
            "import tab resolution: bottom band top>=$TAB_BAND_TOP bottom<=$TAB_BAND_BOTTOM width=$TAB_WIDTH_MIN..$TAB_WIDTH_MAX " +
                "tabWidthMin=$TAB_WIDTH_TAB_MIN rawCandidates=${scan.raw.size} tabCandidates=${scan.tabs.size} " +
                "listWaitMillis=$listWaitMillis",
        )
        for ((index, node) in scan.raw.withIndex()) {
            log("import tab raw candidate[$index] ${describeTabCandidate(node)}")
        }
        for ((index, node) in scan.excluded.withIndex()) {
            log(
                "import tab candidate excluded[$index] (not a tab: width ${boundsOf(node).width()} < $TAB_WIDTH_TAB_MIN): " +
                    describeTabCandidate(node),
            )
        }
        val candidates = collapseTabCandidates(scan.tabs, log)
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
            // The per-attempt success test is the strict both-halves one ([importReviewRendered]), not
            // "some node's text ends with CNY": the latter is satisfied by the HOME screen's transaction
            // lines, so an attempt whose tap did not switch the tab would report `rendered=true` after a
            // few ms and the retry loop below would never run. The bound is the `tabListWaitMillis`
            // argument (default [TAB_LIST_WAIT_MILLIS]): the FIRST full list read at this library's size
            // takes far longer than the 30 s this used to allow, and the elapsed time is logged for a
            // rendered attempt as well as for a failed one.
            val rendered = waitFor({ importReviewRendered(appRoot()) }, listWaitMillis, POLL_MILLIS)
            log("import tab attempt $taps/$TAB_MAX_ATTEMPTS rendered=$rendered elapsedMs=${elapsedMillis(attemptStartNanos)}")
            if (rendered) {
                return true
            }
        }
        log("FINDING: no bottom-band tab attempt rendered the candidate list within $listWaitMillis ms each (taps=$taps)")
        return false
    }

    /**
     * One bottom-band scan, left-to-right in all three lists: [raw] is every node inside the band's
     * width window, [tabs] the ones wide enough to be a tab ([TAB_WIDTH_TAB_MIN]) and [excluded] the ones
     * the tab width floor drops - the new-expense FAB and the tabs' inner decorations. The split is kept
     * rather than only the filtered list so [resolveImportTab] can log what it refused to consider: the
     * FAB tap that navigated away from the import screen (class doc defect 2) has to be visible in the
     * evidence as an exclusion, not as a missing candidate.
     */
    private data class BottomBandScan(
        val raw: List<AccessibilityNodeInfo>,
        val tabs: List<AccessibilityNodeInfo>,
        val excluded: List<AccessibilityNodeInfo>,
    )

    /**
     * The bottom band's TAB candidates, in left-to-right order: the nodes of [scanBottomBand] that are at
     * least [TAB_WIDTH_TAB_MIN] wide. The band and the width window are the geometry fact measured on the
     * managed AVD's 1080x2400 profile (four 183-184 px wide tab nodes whose tops sit at y=2095 and
     * bottoms at y=2305); clickability is deliberately not part of the filter, because the SELECTED tab is
     * not clickable and still has to be identifiable.
     *
     * [TAB_WIDTH_TAB_MIN] is the tab width floor. The band's non-tab nodes are the tabs' 126-127 px inner
     * decorations and the 147 px new-expense FAB, so 170 px separates them from the narrowest tab (183 px)
     * with margin on both sides - and a node the floor drops can never be a candidate (not at index
     * [IMPORT_TAB_INDEX], not as a retry attempt), which is what the FAB needs, because tapping it
     * navigates to the new-expense screen (class doc defect 2).
     */
    private fun bottomBandTabNodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        val scan = scanBottomBand(root)
        return scan.tabs
    }

    /**
     * Scans the bottom band ONCE and splits it, so the resolution's raw logging, its exclusion logging and
     * its candidate list always describe the same tree read. [BottomBandScan.raw] is the band and
     * width-window filter ([TAB_BAND_TOP]..[TAB_BAND_BOTTOM], [TAB_WIDTH_MIN]..[TAB_WIDTH_MAX]) sorted by
     * left edge; the floor is applied to [BottomBandScan.tabs] only, and the nodes it drops are returned
     * as [BottomBandScan.excluded] for the evidence.
     */
    private fun scanBottomBand(root: AccessibilityNodeInfo?): BottomBandScan {
        val inBand =
            collectNodes(root) { node ->
                val bounds = boundsOf(node)
                bounds.top >= TAB_BAND_TOP &&
                    bounds.bottom <= TAB_BAND_BOTTOM &&
                    bounds.width() >= TAB_WIDTH_MIN &&
                    bounds.width() <= TAB_WIDTH_MAX
            }.sortedBy { node -> boundsOf(node).left }
        return BottomBandScan(
            raw = inBand,
            tabs = inBand.filter { node -> boundsOf(node).width() >= TAB_WIDTH_TAB_MIN },
            excluded = inBand.filter { node -> boundsOf(node).width() < TAB_WIDTH_TAB_MIN },
        )
    }

    /**
     * Collapses the band's TAB candidates ([BottomBandScan.tabs], already narrowed by
     * [TAB_WIDTH_TAB_MIN]) to ONE node per tab position, in left-to-right order, which is what makes the
     * index-[IMPORT_TAB_INDEX] fact hold. A device dump of the review screen (the same tree the second run
     * traversed) shows why a tab position can still be exposed more than once after the width floor: each
     * tab box (183-184 x 210 at y=2095..2305) appears twice in the tree - the three unselected ones once
     * clickable and once not, the selected import tab twice non-clickable - so the list this function
     * receives still holds two nodes per position.
     *
     * A candidate whose bounds are contained in (or identical to) a candidate already kept is therefore
     * dropped, and a clickable node at a position replaces a non-clickable node kept there - so each tab
     * position keeps the node that can actually be tapped, and every drop and replacement is logged.
     *
     * The nodes that are NOT tabs never reach this function at all: the tabs' 126-127 px inner decorations
     * and the 147 px new-expense FAB are below [TAB_WIDTH_TAB_MIN] and [scanBottomBand] drops them first,
     * so the FAB - which the run behind class doc defect 2 tapped as a retry - can never be collapsed into
     * a candidate or a retry attempt again.
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
     *
     * Every node this ordering can reach is a TAB: [bottomBandTabNodes] has already applied the
     * [TAB_WIDTH_TAB_MIN] floor, so the 147 px new-expense FAB - the node this distance order tapped in
     * the run behind class doc defect 2 - is not in the list it sorts.
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
     * sees, which packages they belong to, what the active-window root is, whether the import tab is
     * currently reachable, and the import-review-screen predicate's own verdict ([readImportScreen]:
     * present, the branch that matched, and whether a candidate row was seen) so a screen-precondition
     * failure names the half that was missing. Never throws; a broken read is reported as text.
     */
    private fun observedState(): String =
        runCatching {
            val automation = uiAutomation()
            val activeRoot = automation.rootInActiveWindow
            val windowsResult = runCatching { automation.windows }
            val windows = windowsResult.getOrNull().orEmpty()
            val packages = windows.mapNotNull { window -> window.root?.packageName?.toString() }.distinct()
            val root = appRoot()
            val importTabFound = root?.let { node -> findImportTabNode(node) } != null
            val screen = readImportScreen(root)
            val windowError = windowsResult.exceptionOrNull()?.let { error -> " windowsError=${describeError(error)}" }.orEmpty()
            "windows=${windows.size} packages=$packages rootInActiveWindow=${activeRoot?.packageName} importTabFound=$importTabFound " +
                "importScreenPresent=${screen.present} importScreenBranch=${screen.branch} candidateRowSeen=${screen.candidateRow}$windowError"
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

    /**
     * ONE snapshot's import-review-screen reading, taken in a SINGLE tree walk so its halves can never come
     * from different frames. [branch] is the marker that matched ([IMPORT_SCREEN_BRANCH_NONE] when none did)
     * and is logged as evidence; [candidateRow] is whether the same snapshot also held a candidate amount row.
     */
    private data class ImportScreenReading(
        val branch: String,
        val candidateRow: Boolean,
    ) {
        val present: Boolean get() = branch != IMPORT_SCREEN_BRANCH_NONE
    }

    /**
     * Whether ONE snapshot is the import review screen, and which marker says so. The screen half used to be
     * "a node whose text ends with [CURRENCY_SUFFIX]", which the HOME screen's transaction lines satisfy; the
     * markers below are the import screen's OWN and HOME carries none of them (class doc):
     * - the header branch matches a node whose text is exactly [IMPORT_SCREEN_REFRESH_TEXT] or exactly
     *   [IMPORT_SCREEN_PICK_TEXT];
     * - the tolerant fallback matches the candidate row's [CANDIDATE_CHECKBOX_DESC] checkbox AND requires a
     *   candidate amount row in the SAME snapshot, which is what a list that has scrolled its header items
     *   away still carries.
     */
    private fun readImportScreen(root: AccessibilityNodeInfo?): ImportScreenReading {
        val nodes = collectNodes(root) { node -> isImportScreenMarkerNode(node) || isCandidateAmountRowNode(node) }
        val refreshText = nodes.any { node -> nodeText(node) == IMPORT_SCREEN_REFRESH_TEXT }
        val pickText = nodes.any { node -> nodeText(node) == IMPORT_SCREEN_PICK_TEXT }
        val checkboxMarker = nodes.any { node -> node.contentDescription?.toString() == CANDIDATE_CHECKBOX_DESC }
        val candidateRow = nodes.any { node -> isCandidateAmountRowNode(node) }
        val branch =
            when {
                refreshText -> IMPORT_SCREEN_BRANCH_REFRESH
                pickText -> IMPORT_SCREEN_BRANCH_PICK
                checkboxMarker && candidateRow -> IMPORT_SCREEN_BRANCH_CHECKBOX
                else -> IMPORT_SCREEN_BRANCH_NONE
            }
        return ImportScreenReading(branch = branch, candidateRow = candidateRow)
    }

    /** A node carrying an import-screen marker: one of the two header affordances, or the row checkbox. */
    private fun isImportScreenMarkerNode(node: AccessibilityNodeInfo): Boolean {
        val text = nodeText(node)
        return text == IMPORT_SCREEN_REFRESH_TEXT ||
            text == IMPORT_SCREEN_PICK_TEXT ||
            node.contentDescription?.toString() == CANDIDATE_CHECKBOX_DESC
    }

    /** One candidate row's amount line: the test [candidateAmountRows] selects its rows with. */
    private fun isCandidateAmountRowNode(node: AccessibilityNodeInfo): Boolean = isCandidateAmountNode(node) || nodeText(node) == B5_UNRESOLVED_AMOUNT

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
     * One [clickPickerRow] outcome, kept faithful so the caller's evidence line cannot claim an
     * acceptance that never happened: [accepted] is the accessibility click's own result,
     * [coordinateTapIssued] says whether the bounded one-tap fallback was used, and [coordinateTapIssued]
     * without [accepted] is the honest shape of "every level refused, the tap was issued anyway".
     */
    private data class PickerClickOutcome(
        val accepted: Boolean,
        val coordinateTapIssued: Boolean,
    )

    /**
     * Clicks the system file picker's row for [node]. The picker's title node is a non-clickable
     * `TextView` and DocumentsUI does not reliably accept ACTION_CLICK on the row within
     * [CLICK_PARENT_DEPTH] levels, so this walks up to [PICKER_CLICK_PARENT_DEPTH] levels, logging each
     * level's class, enabled/clickable flags and bounds so a refusal is diagnosable. ONE bounded
     * coordinate tap at the row's centre through the shell is the LAST RESORT when every level refuses,
     * and it is a REGISTERED DEVIATION from the accessibility-only rule of this class's traversal: D-161
     * item 2 permits a single navigation tap because the INPUT-FREEZE trigger is a pointer-gesture
     * STORM, and this is one tap, not a storm. The tap is UNVERIFIED - nothing here can confirm that it
     * landed - so the returned outcome reports `accepted=false coordinateTapIssued=true` for it rather
     * than a success, and whether the picker actually advanced is readable only from the intake that
     * follows ([awaitCountProbeIntake]).
     */
    private fun clickPickerRow(
        node: AccessibilityNodeInfo,
        log: (String) -> Unit,
    ): PickerClickOutcome {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth <= PICKER_CLICK_PARENT_DEPTH) {
            val levelDescription =
                "picker click level=$depth class=${current.className} enabled=${current.isEnabled} clickable=${current.isClickable} visible=${current.isVisibleToUser} bounds=${boundsOf(current)}"
            log(levelDescription)
            if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                log("picker click accepted at level=$depth")
                return PickerClickOutcome(accepted = true, coordinateTapIssued = false)
            }
            current = current.parent
            depth += 1
        }
        val bounds = boundsOf(node)
        log(
            "picker click refused at every level; falling back to ONE coordinate tap at (${bounds.centerX()}, ${bounds.centerY()}) - " +
                "a registered deviation from the accessibility-only rule, and the tap is unverified",
        )
        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
        return PickerClickOutcome(accepted = false, coordinateTapIssued = true)
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
