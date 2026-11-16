package com.unifiedledger.android

import android.content.ContentValues
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.AtomicFile
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unifiedledger.application.ImportCandidateConfirmRequest
import com.unifiedledger.application.ImportCandidateDecisionResult
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportConfirmDecisionFields
import com.unifiedledger.application.ImportDuplicateCandidateId
import com.unifiedledger.application.ImportDuplicateReviewRequest
import com.unifiedledger.application.ImportDuplicateReviewResult
import com.unifiedledger.application.ImportDuplicateStatus
import com.unifiedledger.application.ImportFileIntakeInput
import com.unifiedledger.application.ImportFileIntakeOutcome
import com.unifiedledger.application.ImportFormatCapabilities
import com.unifiedledger.application.ImportIntakeRecordDisposition
import com.unifiedledger.application.ImportPlatformKind
import com.unifiedledger.application.ImportRequestId
import com.unifiedledger.application.ImportRequestIdentity
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.ui.ImportFilePickResultChannel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * D-320: the jump accelerator's candidate index WITHIN the review list's first
 * loaded page. The target is that page's first confirmable row (the session
 * imported first), so the index must stay inside the loaded page: `0 until` the
 * page's candidate count (`IMPORT_REVIEW_PAGE_SIZE` = 50 in `P503App.kt`). 0 is
 * chosen because the scan never moves backward — an overshoot past the target is
 * unrecoverable — and the accelerator's `+ 12` item slack lands in the list's
 * non-candidate preamble either way.
 */
private const val FIRST_PAGE_JUMP_INDEX = 0

/**
 * D-328 first-window probe: the `:652` await's own sampling budget. The await is the
 * measurement instrument, so its observers may only touch the evidence and logcat; the
 * predicate must see the SAME `has()` the unmodified wait uses. 10s between text
 * samples keeps the array small on a 900s wait (at most ~90 entries; a card that opens
 * early leaves only the final sample).
 */
private const val GROUP_AWAIT_SAMPLE_INTERVAL_MS = 10_000L

/**
 * D-328 first-window probe: logcat lines land under the repository's `ULStartup` tag
 * (StartupTrace.android actual + the AndroidScaleOracle.journalMode precedent), so the
 * failure round's existing `ULStartup` grep keeps covering the probe without a new tag.
 */
private const val GROUP_ENUM_TRACE_TAG = "ULStartup"

/**
 * D-328 first-window probe: the click-to-card await's rendered target
 * (groupDispositionCardHeaderDisclosure, P503ImportReviewPresentation) — the exact
 * predicate text, so the capture key and the trace rows quote the same contract.
 */
private fun groupCardDisclosureText(relations: Int): String = "将逐条提交人工审核判定为重复，每条独立生效；某一条失败不影响其余各条。共 $relations 条。"

/** Five host-separated phases; the continuous UI chain has no process restart or DB reset. */
@RunWith(AndroidJUnit4::class)
class AndroidScaleLongInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val phase = checkNotNull(arguments.getString("scalePhase"))
    private val sha = checkNotNull(arguments.getString("expectedSha"))
    private val deadline = checkNotNull(arguments.getString("deadlineElapsedMs")).toLong()
    private val evidenceFile = File(context.filesDir, "android-scale-evidence.json")
    private val stateFile = File(context.filesDir, "android-scale-state.json")
    private lateinit var state: JSONObject
    private lateinit var evidence: JSONObject
    private val oracle by lazy { AndroidScaleOracle(context, ::tick) }
    private val ui by lazy { AndroidScaleUi(instrumentation, ::tick) }

    // D-216: every scale expectation derives from the generated manifest, so the
    // same device test drives `maximum` (cloud) and `local-small` (local
    // diagnostic) without hard-coding maximum's oracle literals.
    private val spec by lazy {
        val manifest = JSONObject(File(File(context.filesDir, "scale-fixture"), "manifest.json").readText())
        val parsed =
            AndroidScaleFixtureSpec(
                profile = manifest.getString("profile"),
                seed = manifest.getLong("seed"),
                initialSessions = manifest.getInt("initial_sessions"),
                rowsPerSession = manifest.getInt("rows_per_session"),
                uniqueRows = manifest.getInt("unique_rows"),
                mainSessionRows = manifest.getInt("main_session_rows"),
                initialCandidates = manifest.getInt("initial_candidates"),
                finalCandidates = manifest.getInt("final_candidates"),
                initialDuplicateRelations = manifest.getInt("initial_duplicate_relations"),
                finalDuplicateRelations = manifest.getInt("final_duplicate_relations"),
                newSessionDuplicateRelations = manifest.getInt("new_session_duplicate_relations"),
                initiallyConfirmedRelations = manifest.getInt("initially_confirmed_relations"),
            )
        check(parsed.isValid()) { "unsupported scale fixture manifest" }
        parsed
    }
    private var phaseDeadline = Long.MAX_VALUE

    @Test
    fun maximumScalePhase() {
        check(Regex("[0-9a-f]{40}").matches(sha))
        evidence =
            if (evidenceFile.exists()) {
                JSONObject(evidenceFile.readText())
            } else {
                JSONObject()
                    .put("schema", 1)
                    .put("sha", sha)
                    .put("stages", JSONObject())
            }
        check(evidence.getString("sha") == sha)
        state = if (stateFile.exists()) JSONObject(stateFile.readText()) else JSONObject()
        try {
            when (phase) {
                "prepare" -> stage("preparation", 14400000) { prepare() }
                "chain" -> chain()
                // D-217 round 21 (closure review): the reopen phase registers
                // two SIBLING stages — the quiescent reopen stage and, after
                // it fully completes, the traversal walk stage. The walk used
                // to be nested INSIDE the reopen stage's action, which broke
                // result.py two ways: its STAGES order requires strictly
                // increasing startedMs across stages, so a traversal that
                // starts after reopen started can never validate (reopen is
                // validated after the walk in the tuple), and reopen's
                // elapsedMs swallowed the up-to-4h walk under a 180s default
                // cap that result.py's limits has no reopen entry for. The
                // dispatch also keeps both stages exactly-once: reopenPhase()
                // runs only for phase "reopen", and final-reopen re-enters
                // the shared assertions through finalReopen(), which
                // registers only "final_reopen" — the "stage must not be
                // retried" guard can never fire.
                "reopen" -> reopenPhase()
                "replay" -> stage("same_request_replay") { replay() }
                "final-reopen" -> stage("final_reopen") { finalReopen() }
                else -> error("unknown scale phase")
            }
        } finally {
            save()
        }
    }

    private fun tick() {
        check(SystemClock.elapsedRealtime() < minOf(deadline, phaseDeadline)) { "scale deadline exceeded" }
    }

    private fun save() {
        fun atomic(
            file: File,
            value: JSONObject,
        ) {
            val target = AtomicFile(file)
            val output = target.startWrite()
            try {
                output.write(value.toString().toByteArray())
                target.finishWrite(output)
            } catch (failure: Throwable) {
                target.failWrite(output)
                throw failure
            }
        }
        if (state.length() > 0) atomic(stateFile, state)
        atomic(evidenceFile, evidence)
    }

    private fun <T> stage(
        name: String,
        limitMs: Long = 180000,
        action: () -> T,
    ): T {
        val stages = evidence.getJSONObject("stages")
        check(!stages.has(name)) { "stage must not be retried" }
        val started = SystemClock.elapsedRealtime()
        // D-217 round 21 (closure review): save the enclosing phase deadline
        // before overwriting it, so this stage's finally restores the outer
        // stage's remaining budget instead of erasing it to Long.MAX_VALUE
        // for the rest of the outer body.
        val previousDeadline = phaseDeadline
        phaseDeadline = minOf(deadline, started + limitMs)
        stages.put(name, JSONObject().put("status", "NOT_RUN").put("startedMs", started))
        evidence.put("activeDeadlineElapsedMs", phaseDeadline)
        save()
        try {
            tick()
            val result = action()
            tick()
            stages.getJSONObject(name).put("status", "PASS")
            return result
        } catch (failure: Throwable) {
            val assertion = failure is AssertionError || (failure is IllegalStateException && failure.message?.contains("deadline") != true)
            stages
                .getJSONObject(name)
                .put("status", if (assertion) "FAIL" else "ERROR")
                .put("errorType", failure.javaClass.simpleName)
            // D-206 stage forensics: failure-instant screenshot + a11y snapshot,
            // diagnostics only and fully contained (it never throws, never ticks
            // and never masks this original failure). coldstart keeps its own
            // richer D-202 capture with the sample buffer, so it is excluded.
            if (name != "coldstart") AndroidScaleStageForensics.capture(instrumentation, { ui }, sha, name, failure)
            throw failure
        } finally {
            stages.getJSONObject(name).put("elapsedMs", SystemClock.elapsedRealtime() - started)
            phaseDeadline = previousDeadline
            evidence.remove("activeDeadlineElapsedMs")
            save()
        }
    }

    /**
     * D-328 first-window probe (run 43587072230 attempt 2): the group-disposition card
     * never landed within the default 180s await at `:652` — the screenshot showed only
     * the 正在整理重复组…… progress line, and the P1 trace's every-7s `review.groupGate`
     * sample held `count=value=50000 inputRef=present entry=true loadedGroupRows=0` for
     * the whole wait, so the 50,000-relation enumeration's first window never reached the
     * composition. This probe is measurement-only: the probe object changes no predicate,
     * click, or product behavior; the call site alone raises this await's wait ceiling to
     * 900s (measured, stage budget untouched).
     *
     * Milestone trace (deliverable 1): the product emits no group-enumeration milestone
     * lines of its own — the only in-area hook is the per-recomposition
     * `review.groupGate` count line (P503ImportReviewPresentation), which already carries
     * the count and inputRef presence — so the round's interior milestones are
     * APPROXIMATED from the visible UI state and elapsed time on the await's `onPoll`
     * hook, and every approximated row is tagged `source=onPoll inferred` (await's doc
     * calls the hook coldstart-only; this is its first other mount, superseded by D-328
     * in this call site's comment). The mapping, honest about what the UI can prove:
     * - `click received` — test-owned, emitted at construction right after the click.
     * - `lease acquired` — first sighting of the 正在整理重复组…… line, which renders only
     *   between the host's ImportGroupEnumerationStarted/Completed mirror; `lease denied`
     *   is recorded by [end] when a non-open outcome ends the wait with no such sighting.
     * - `count done` / `first member page done` / `first window dispatched` — the UI
     *   cannot separate the pre-click count read (the D-322 gate had a real count before
     *   the button existed), the first member-page reads and the first-window dispatch;
     *   all three surface together as the card's disclosure, so all three rows share one
     *   timestamp, with the count parsed from the rendered 共 N 条 copy.
     * - `round ended` — test-owned, emitted by [end] on both the open and the rethrown
     *   failure paths.
     *
     * Evidence capture (deliverable 3): each sample holds the top window's visible lines
     * containing the progress line, the affordance or the card copy, at most one sample
     * per [GROUP_AWAIT_SAMPLE_INTERVAL_MS], under the evidence key
     * `groupCardAwaitSamples` (array shape follows the androidTest evidence arrays, e.g.
     * AndroidScaleObserverDiagWriter). `end` adds a final sample on every path, so the
     * decisive rendered text lives through a timeout — the stage's `save()` in its
     * finally persists it (the D-326 lesson: the failure forensics' texts block
     * truncates). Only rendered copy is quoted — never an inputRef or user data.
     *
     * Containment: [onPoll] never ticks (the wait owns the deadline) and swallows every
     * observation failure so it cannot replace the wait's own outcome, exactly like
     * AndroidScaleUi's contained resets; [end] is equally contained because it runs on
     * the rethrow path and must not mask the original failure.
     */
    private class GroupCardAwaitProbe(
        private val ui: AndroidScaleUi,
        private val evidence: JSONObject,
        relations: Int,
    ) {
        private val startedMs = SystemClock.elapsedRealtime()
        private val disclosure = groupCardDisclosureText(relations)
        private val samples = JSONArray()
        private var lastSampleMs = 0L
        private var sawEnumerationStarted = false
        private var sawCard = false

        init {
            // Test-owned milestone: the click was dispatched (the seek back to top and
            // the button seek already ran before the probe is constructed).
            Log.i(GROUP_ENUM_TRACE_TAG, "scale.groupEnum click received relations=$relations")
        }

        /** The top target window's visible lines in the probe's marker set, viewport-sized. */
        private fun matchingLines(): List<String> =
            ui
                .visibleTexts(ui.root())
                .filter { line ->
                    line.contains("正在整理重复组") || line.startsWith("整组标记为重复") || line.contains("将逐条提交人工审核")
                }

        fun onPoll() {
            val elapsed = SystemClock.elapsedRealtime() - startedMs
            val joined =
                try {
                    matchingLines().joinToString("|")
                } catch (failure: Throwable) {
                    Log.i(GROUP_ENUM_TRACE_TAG, "scale.groupEnum sample failed ms=$elapsed causeClass=${failure::class.simpleName}")
                    ""
                }
            if (!sawEnumerationStarted && joined.contains("正在整理重复组")) {
                sawEnumerationStarted = true
                Log.i(GROUP_ENUM_TRACE_TAG, "scale.groupEnum lease acquired ms=$elapsed source=onPoll inferred")
            }
            if (!sawCard && joined.contains(disclosure)) {
                sawCard = true
                // The count value is parsed from the rendered disclosure copy (not user
                // data); the count query itself predates the click — the product's own
                // review.groupGate lines carry its timing. The member-page read is NOT
                // observable from the UI, so its row carries rows=unknown: onPoll can
                // prove only that the first window rendered, not how many rows the read
                // returned.
                val count = Regex("共 ([0-9]+) 条").find(joined)?.groupValues?.get(1) ?: "unknown"
                Log.i(GROUP_ENUM_TRACE_TAG, "scale.groupEnum count done ms=$elapsed count=$count source=onPoll inferred")
                Log.i(GROUP_ENUM_TRACE_TAG, "scale.groupEnum first member page done ms=$elapsed rows=unknown source=onPoll inferred")
                Log.i(GROUP_ENUM_TRACE_TAG, "scale.groupEnum first window dispatched ms=$elapsed source=onPoll inferred")
            }
            if (elapsed - lastSampleMs >= GROUP_AWAIT_SAMPLE_INTERVAL_MS) {
                lastSampleMs = elapsed
                samples.put(
                    JSONObject()
                        .put("atMs", elapsed)
                        .put("text", if (joined.isEmpty()) "none" else joined.take(2000)),
                )
                evidence.put("groupCardAwaitSamples", samples)
            }
        }

        /** Final capture plus the round-ended trace; never throws (runs on the rethrow path). */
        fun end(outcome: String) {
            val elapsed = SystemClock.elapsedRealtime() - startedMs
            try {
                val joined =
                    try {
                        matchingLines().joinToString("|")
                    } catch (failure: Throwable) {
                        Log.i(GROUP_ENUM_TRACE_TAG, "scale.groupEnum final sample failed ms=$elapsed causeClass=${failure::class.simpleName}")
                        ""
                    }
                samples
                    .put(
                        JSONObject()
                            .put("atMs", elapsed)
                            .put("text", if (joined.isEmpty()) "none" else joined.take(2000))
                            .put("final", true),
                    )
                evidence.put("groupCardAwaitSamples", samples)
                if (!sawEnumerationStarted && outcome != "card-opened") {
                    Log.i(GROUP_ENUM_TRACE_TAG, "scale.groupEnum lease denied ms=$elapsed source=onPoll inferred (no enumeration progress observed)")
                }
            } catch (failure: Throwable) {
                Log.i(GROUP_ENUM_TRACE_TAG, "scale.groupEnum end capture failed ms=$elapsed causeClass=${failure::class.simpleName}")
            }
            // sawCard is the visibleTexts walk's own verdict; when the judging predicate
            // (ui.has, with its cache-reset recovery) succeeded while this walk never saw
            // the disclosure, the divergence itself is evidence of the a11y blind spot —
            // record it on the round-ended row.
            val walkVerdict = if (sawCard) "walk-saw-card" else "walk-blind"
            Log.i(GROUP_ENUM_TRACE_TAG, "scale.groupEnum round ended ms=$elapsed outcome=$outcome walk=$walkVerdict")
        }
    }

    /**
     * D-331 deliverable 2 — the group-mark OUTCOME-visibility probe (run 43642845302: the
     * 确认整组标记 click succeeded, then the `已标记：CONFIRMED_DUPLICATE` await timed out at its
     * default 120s with the failure forensics' viewport parked inside the card's member-row
     * region and targetHit=false). The open question the probe answers: do the 50,000 per-item
     * outcomes render FIRST-ITEM-IMMEDIATELY (a viewport problem) or in a WHOLE-LIST CADENCE (a
     * measurement problem)? Measurement-only, the D-328 [GroupCardAwaitProbe] discipline: the
     * probe changes no predicate, click, or product behavior; the call site alone raises this
     * await's ceiling to 600s (measured, inside the 5,400s stage budget).
     *
     * Trace (`scale.groupMark`, the [GROUP_ENUM_TRACE_TAG] tag so the round's existing ULStartup
     * grep covers it):
     * - `click accepted` — test-owned, emitted at construction right after the click.
     * - `round ended outcome=<outcome-hit|await-timeout|await-error> firstHitMs=<n>` — test-owned,
     *   emitted by [end] on both the hit and the rethrown-failure paths; `firstHitMs` is the
     *   sample walk's own first sighting of the outcome row (absent → the walk stayed blind, the
     *   same walk-vs-predicate divergence evidence the D-328 probe records).
     *
     * Evidence capture: each sample holds the top window's visible lines containing the outcome
     * row's three copy forms (待处置 / 已标记： / 失败) with their per-form counts, at most one
     * sample per [GROUP_AWAIT_SAMPLE_INTERVAL_MS], under the evidence key `groupMarkOutcomeSamples`;
     * `firstOutcomeHitMs` is its own evidence key. `end` adds a final sample on every path —
     * the final put rides the same write as the main capture (one contained try), so on the
     * SUCCESS path the final sample always lands (the D-326 lesson: the failure forensics'
     * texts block truncates; a put failure means the evidence object itself is unwritable and
     * only the trace row records it). Only rendered copy is quoted — never an inputRef or user
     * data.
     *
     * Containment: [onPoll] never ticks (the wait owns the deadline) and swallows every
     * observation failure so it cannot replace the wait's own outcome; [end] is equally contained
     * because it runs on the rethrow path and must not mask the original failure.
     */
    private class GroupMarkOutcomeProbe(
        private val ui: AndroidScaleUi,
        private val evidence: JSONObject,
    ) {
        private val startedMs = SystemClock.elapsedRealtime()
        private val samples = JSONArray()
        private var lastSampleMs = 0L
        private var firstOutcomeHitMs = -1L

        init {
            // Test-owned milestone: the click was dispatched (the reverse seek already ran
            // before the probe is constructed).
            Log.i(GROUP_ENUM_TRACE_TAG, "scale.groupMark click accepted")
        }

        /** The top target window's visible outcome-copy lines, viewport-sized, with counts. */
        private fun sampleLines(): Pair<String, JSONObject> {
            val lines = ui.visibleTexts(ui.root()).filter { line -> line.contains("待处置") || line.startsWith("已标记：") || line.startsWith("失败") }
            val counts = JSONObject()
            var pending = 0
            var marked = 0
            var failed = 0
            lines.forEach { line ->
                when {
                    line.contains("待处置") -> pending++
                    line.startsWith("已标记：") -> marked++
                    else -> failed++
                }
            }
            counts.put("pending", pending).put("marked", marked).put("failed", failed)
            return lines.joinToString("|") to counts
        }

        fun onPoll() {
            val elapsed = SystemClock.elapsedRealtime() - startedMs
            try {
                val (joined, counts) = sampleLines()
                if (firstOutcomeHitMs < 0 && counts.optInt("marked") > 0) {
                    firstOutcomeHitMs = elapsed
                    Log.i(GROUP_ENUM_TRACE_TAG, "scale.groupMark first outcome row ms=$elapsed source=onPoll inferred")
                }
                if (elapsed - lastSampleMs >= GROUP_AWAIT_SAMPLE_INTERVAL_MS) {
                    lastSampleMs = elapsed
                    samples.put(
                        JSONObject()
                            .put("atMs", elapsed)
                            .put("text", if (joined.isEmpty()) "none" else joined.take(2000))
                            .put("counts", counts),
                    )
                    evidence.put("groupMarkOutcomeSamples", samples)
                }
            } catch (failure: Throwable) {
                Log.i(GROUP_ENUM_TRACE_TAG, "scale.groupMark sample failed ms=$elapsed causeClass=${failure::class.simpleName}")
            }
        }

        /** Final capture plus the round-ended trace; never throws (runs on the rethrow path). */
        fun end(outcome: String) {
            val elapsed = SystemClock.elapsedRealtime() - startedMs
            try {
                val (joined, counts) =
                    try {
                        sampleLines()
                    } catch (failure: Throwable) {
                        Log.i(GROUP_ENUM_TRACE_TAG, "scale.groupMark final sample failed ms=$elapsed causeClass=${failure::class.simpleName}")
                        "" to JSONObject().put("pending", 0).put("marked", 0).put("failed", 0)
                    }
                samples
                    .put(
                        JSONObject()
                            .put("atMs", elapsed)
                            .put("text", if (joined.isEmpty()) "none" else joined.take(2000))
                            .put("counts", counts)
                            .put("final", true),
                    )
                evidence.put("groupMarkOutcomeSamples", samples)
                if (firstOutcomeHitMs >= 0) {
                    evidence.put("firstOutcomeHitMs", firstOutcomeHitMs)
                }
            } catch (failure: Throwable) {
                // Put failed → the evidence object itself is unwritable: record the trace
                // row and stop — the final sample rides the same put as the main capture,
                // so a failed put loses both together (no follow-up append can succeed
                // either; the D-328 end() containment, D-326's evidence-must-survive lesson
                // makes the final sample a hard invariant of the SUCCESS path).
                Log.i(GROUP_ENUM_TRACE_TAG, "scale.groupMark end capture failed ms=$elapsed causeClass=${failure::class.simpleName}")
            }
            // The walk's own first-sighting verdict: when the judging predicate (ui.has, with
            // its cache-reset recovery) succeeded while this walk never saw the outcome row,
            // the divergence itself is evidence of the a11y blind spot — record it via
            // firstHitMs=-1 on the round-ended row (the D-328 walk-verdict precedent).
            val firstHitMs = if (firstOutcomeHitMs >= 0) firstOutcomeHitMs else -1L
            Log.i(GROUP_ENUM_TRACE_TAG, "scale.groupMark round ended ms=$elapsed outcome=$outcome firstHitMs=$firstHitMs")
        }
    }

    private fun openGraph(): CloseableLedgerGraph =
        openAndroidStableStorageLedger(
            context,
            AndroidImportFilePickPort<Uri>(
                launchOpenDocument = { error("preparation must not launch UI") },
                resolveMetadata = { error("no pick in preparation") },
                openInputStream = { error("no pick in preparation") },
                onResult = { error("no pick in preparation") },
            ),
            ImportFilePickResultChannel(),
        )

    private fun prepare() {
        // Only a new CI-installed app may be prepared. No clearing of a prior ledger.
        check(state.length() == 0)
        check(scaleWindowOffset(listOf("a", "b", "c"), listOf("b", "c"), 0, 2) == 1)
        check(scaleWindowOffset(listOf("a", "b", "c"), listOf("c"), 0, 2) == null)
        check(scaleWindowOffset(listOf("a", "a", "b"), listOf("a"), 0, 2) == null)
        val fixtureRoot = File(context.filesDir, "scale-fixture")
        val manifest = JSONObject(File(fixtureRoot, "manifest.json").readText())
        check(manifest.getInt("schema_version") == 2 && manifest.getString("profile") == spec.profile)
        check(manifest.getInt("final_candidates") == spec.finalCandidates && manifest.getInt("final_duplicate_relations") == spec.finalDuplicateRelations)
        val files = manifest.getJSONObject("files")
        val names = (1..spec.stateSessions).map { "session-" + it.toString().padStart(2, '0') + ".csv" } + "unique-rows.csv"
        check(files.keys().asSequence().toSet() == names.toSet())
        names.forEach { name ->
            val bytes = File(fixtureRoot, name).readBytes()
            check(bytes.size == files.getJSONObject(name).getInt("bytes"))
            check(scaleDigest(bytes) == files.getJSONObject(name).getString("sha256"))
        }
        state.put("seed", manifest.getLong("seed")).put("sessions", JSONArray())
        val graph = openGraph()
        try {
            val facade = graph.facade
            val ledger = facade.ledgerId.value
            state.put("ledger", ledger)
            val empty = oracle.snapshot(ledger)
            check(empty.rows.isEmpty() && empty.relations == 0)
            oracle.zeroEconomics(empty)
            state.put("generation", empty.generation)
            val options = facade.optionsProvider.queryOptions()
            val category = options.expenseCategories.first()
            val account = options.paymentAccounts.first { it.currency.code == "CNY" }
            state
                .put("categoryId", category.categoryId.value)
                .put("categoryLabel", category.label)
                .put("expenseAccountId", category.postingAccountId.value)
                .put("accountId", account.accountId.value)
                .put("accountLabel", account.label)
            val preparation = names.take(spec.initialSessions) + "unique-rows.csv"
            preparation.forEachIndexed { index, name ->
                val started = SystemClock.elapsedRealtime()
                evidence
                    .put("activeDeadlineElapsedMs", minOf(deadline, started + 1800000))
                    .put("activeOperation", "prepare-intake-${index + 1}")
                save()
                val session = checkNotNull(facade.importIntakeSessionFactory())
                val result =
                    checkNotNull(facade.importFileIntake).intake(
                        ImportFileIntakeInput(ImportFormatCapabilities.ALIPAY_CSV.identifier, ImportPlatformKind.ANDROID, session, File(fixtureRoot, name).readBytes()),
                    )
                tick()
                check(SystemClock.elapsedRealtime() - started <= 1800000) { "preparation intake exceeded 30 minutes" }
                check(result is ImportFileIntakeOutcome.Accepted)
                val count = spec.sessionRows(index)
                check(result.records.size == count && result.newCandidateIds.size == count)
                result.records.forEachIndexed { ordinal, row ->
                    check(row.recordOrdinal == ordinal && row.disposition == ImportIntakeRecordDisposition.INTAKE_ACCEPTED)
                }
                state.getJSONArray("sessions").put(
                    JSONObject()
                        .put("inputRef", session.inputRef)
                        .put("candidateIds", JSONArray(result.newCandidateIds.map { it.value })),
                )
                graph.runFullAnalyze()
                evidence.put("activeDeadlineElapsedMs", phaseDeadline).remove("activeOperation")
                save()
            }
            val initial = oracle.snapshot(ledger)
            oracle.assertFixture(initial, state, spec, final = false)
            oracle.zeroEconomics(initial)
            check(initial.confirmedRelations.isEmpty())
            val selected =
                oracle.relationIds(
                    ledger,
                    state.getJSONArray("sessions").getJSONObject(1).getString("inputRef"),
                    spec.initiallyConfirmedRelations,
                )
            check(selected.size == spec.initiallyConfirmedRelations)
            state.put("preparedRelationIds", JSONArray(selected.map { it.first }))
            selected.forEach { (id, fingerprint) ->
                tick()
                val ids = checkNotNull(facade.importDuplicateReviewIds())
                val result =
                    checkNotNull(facade.importDuplicateReview).execute(
                        ImportDuplicateReviewRequest(
                            ImportRequestIdentity(facade.ledgerId, ids.requestId),
                            ImportDuplicateCandidateId(id),
                            fingerprint,
                            ImportDuplicateStatus.CONFIRMED_DUPLICATE,
                            "user-reviewed",
                            "2026-01-16T08:00:00+08:00",
                            "scale-test-review",
                            "2026-01-16T08:00:00+08:00",
                            ids.reviewId,
                            ids.historyId,
                        ),
                    )
                check(result is ImportDuplicateReviewResult.Accepted)
            }
            val prepared = oracle.snapshot(ledger, compareReadPath = true)
            oracle.assertFixture(prepared, state, spec, final = false)
            check(prepared.confirmedRelations == selected.map { it.first }.toSet())
            check(prepared.identityDigest == initial.identityDigest && prepared.relationDigest == initial.relationDigest)
            check(prepared.counts.getValue("import_duplicate_status_history") == spec.duplicateHistoryAfterPrepare)
            check(prepared.counts.getValue("import_duplicate_review_receipt") == spec.duplicateReviewReceiptAfterPrepare)
            oracle.zeroEconomics(prepared)
            state.put("preparedIdentity", prepared.identityDigest)
            state.put("preparedRelationships", oracle.relationshipBaseline(ledger, preparedSessions()))
            evidence
                .put("preparedCandidates", spec.initialCandidates)
                .put("preparedRelations", spec.initialDuplicateRelations)
                .put("preparedDispositions", spec.initiallyConfirmedRelations)
        } finally {
            graph.close()
        }
        // Publish only the final fixture through MediaStore so DocumentsUI can actually find it.
        val mainSessionName = spec.mainSessionFileName
        // D-216 local channel: every prepare published the same display name,
        // and MediaStore deduplicates with " (n)" suffixes — the exact name the
        // picker matches eventually ceased to exist (14 numbered copies on the
        // local AVD). Remove this run's prior publishes first; the cloud's
        // fresh emulator never accumulates them.
        val base = mainSessionName.removeSuffix(".csv")
        context.contentResolver.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
            arrayOf("$base%"),
        )
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, mainSessionName)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        val uri = checkNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        checkNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(File(fixtureRoot, mainSessionName).readBytes()) }
        check(context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1)
    }

    /**
     * Coldstart-only bounded forensics: sampling during the launch wait and one
     * best-effort capture after the first failure, before rethrow. The D-205
     * observer diagnostic is attached to the same wait path only: it records
     * one entry per forced client-cache reset (see [AndroidScaleUi.await]).
     * Every other launch/await call site keeps its exact behavior; the stage
     * verdict, the original Throwable and the remaining NOT_RUN stages never
     * change.
     */
    private fun coldstartLaunch() {
        val forensics = AndroidScaleColdstartForensicsCollector(instrumentation, ui, sha)
        val observerDiag = AndroidScaleObserverDiagWriter(instrumentation, sha)
        try {
            ui.launch(onPoll = { forensics.sample() }, diagnostic = observerDiag)
        } catch (failure: Throwable) {
            forensics.capture(failure)
            throw failure
        }
    }

    private fun preparedSessions(): List<String> =
        state.getJSONArray("sessions").let { sessions ->
            (0 until sessions.length()).map { sessions.getJSONObject(it).getString("inputRef") }
        }

    /**
     * D-320: the session imported first (session-01). Its rows fill the review
     * list's first loaded page (`maximum`) or lead it (the smaller diagnostic
     * profiles), so the chain's detail target is one of its candidates.
     */
    private fun firstPageSessionInputRef(): String = state.getJSONArray("sessions").getJSONObject(0).getString("inputRef")

    /**
     * D-320: the spec-derived amount range of the session imported first. Each
     * of its candidates renders `seed + 1 + ordinal + sessionAmountOffset(0)`
     * for ordinals in `0 until rowsPerSession`, so every first-page amount is
     * inside this range and the range is derived from the manifest — never a
     * hand-written amount. The range stays disjoint from the unique-rows
     * session's range (its offset is `rowsPerSession`), which is what keeps the
     * chain's traversal anchor unique.
     */
    private fun firstPageAmountRange(): LongRange {
        val base = state.getLong("seed") + 1L + spec.sessionAmountOffset(0)
        return base until base + spec.rowsPerSession
    }

    /**
     * D-217 review fix: the unique-rows session's ordinal-0 amount, the traversal
     * walk's spec-derived anchor (D-320 keeps it for that role only; the detail
     * target is a first-page candidate instead). Its rows start after the shared
     * range ([AndroidScaleFixtureSpec.sessionAmountOffset]), so the amount is
     * seed + 1 + rowsPerSession — disjoint from every shared session's
     * seed+1..seed+rowsPerSession range, so the anchor appears exactly once during
     * the walk.
     */
    private fun uniqueSessionFirstAmount(): Long = state.getLong("seed") + 1L + spec.sessionAmountOffset(spec.initialSessions)

    private fun assertPreparedUnchanged(snapshot: ScaleSnapshot) {
        val sessions = preparedSessions()
        check(snapshot.copy(rows = snapshot.rows.filter { it.session in sessions }).identityDigest == state.getString("preparedIdentity"))
        check(oracle.relationshipBaseline(state.getString("ledger"), sessions) == state.getString("preparedRelationships"))
        val ids = state.getJSONArray("preparedRelationIds")
        check((0 until ids.length()).all { ids.getString(it) in snapshot.confirmedRelations })
    }

    private fun chain() {
        check(evidence.getJSONObject("stages").getJSONObject("preparation").getString("status") == "PASS")
        stage("coldstart") {
            coldstartLaunch()
            // Entry-point claim via the LedgerReadyCopy prefixes, not a copy pin: D-307 keyed the
            // ledger-wide empty voice on the LEDGER transaction total (P503OverviewScreen.kt:124-128),
            // so an empty ledger paints 账本为空，还没有任何交易。 and the flow header never renders
            // there; a non-empty ledger paints 流水 · 共 <n> 笔. The device run 43304276874 caught this
            // assertion pinning the flow-header string alone and failing on a healthy, empty ledger
            // (its home dump carries 账本为空 with the drawer, search capsule and month row rendered).
            // LedgerReadyCopy.matches is the same readiness half ui.launch already awaited and the
            // Python READY_MARKERS guard keeps in sync across the language boundary.
            check(ui.visibleTexts(ui.root()).any { text -> LedgerReadyCopy.matches(text) })
            // D-217: the only in-chain oracle read. The ledger is empty here —
            // no writer exists, so the read cannot hit the D-212 write fence —
            // and the empty snapshot anchors the generation for later phases.
            val ledger = state.getString("ledger")
            oracle.zeroEconomics(oracle.snapshot(ledger))
        }
        // D-217: the in-chain stages observe the UI only. Every oracle read
        // against the live app died at the D-212 write-fence wall (high
        // frequency short writes x the big read's many query pages, each page
        // silently eating the busy timeout); the heavy SQL assertions moved
        // verbatim to the reopen phase's quiescent reopen stage, which runs
        // while the app is force-stopped (round 21 reword: only the
        // post-launch tail read runs against the live app — there the guard
        // is post-chain quiescence, not the force-stop).
        stage("saf_import", 1800000) {
            ui.openImportViaDrawer()
            ui.selectSafFixture(spec.mainSessionFileName)
            ui.await(1800000) { ui.has("接治完成：新增 " + spec.mainSessionRows + "，等价重放 0，解析拒绝 0，接治拒绝 0。") }
            ui.click("刷新清单")
            ui.settle()
            // D-217 review fix: this stage performs NO oracle read (D-212 wall),
            // so it must not claim a measured candidate/relation count by echoing
            // the spec — that would make cross_check_manifest compare the manifest
            // against itself. finalCandidates/finalRelations are measured in
            // reopen() from the quiescent snapshot.
        }
        // D-216: the local-small profile navigates without the indexed jump
        // (the client never exposes it here), so the deterministic scan is the
        // whole mechanism and needs more than the 180s the cloud contract caps
        // detail_decision at (result.py keeps that cap for maximum; the outer
        // budget still governs the local run).
        // D-320: the target is the review list's first-page candidate, not a deep
        // row. Preparation imports the shared sessions first, so the page the
        // paged read loads (D-293) begins with session-01's rows; session-01 is
        // the only shared session imported before any duplicate comparison
        // exists, so its candidates fold no duplicate status at all and stay
        // PENDING_USER_DECISION — selectable, with the decision form. The row's
        // amount is read from the rendered row and checked against the spec's
        // first-session range; see `openFirstPageCandidate`.
        stage("detail_decision", if (spec.profile == "maximum") 420000L else 600000L) {
            val amount = ui.openFirstPageCandidate(firstPageAmountRange(), FIRST_PAGE_JUMP_INDEX)
            state.put("selectedAmount", amount)
            check(ui.has("决策未补全，尚不可提交确认。"))
            // The checkbox click must be verified by its effect: a click on a
            // stale client-cache node fails silently (D-216 local finding), and
            // the screen carries no scrollable container for seek's fallback.
            ui.clickUntil("勾选候选", "进入批量确认")
            ui.click("进入批量确认")
            ui.click("授权逐项入账")
            ui.await { ui.has("导入", prefix = true) }
            // D-217 round 16 (evidence27/28/29): the a11y event feed stalls
            // for tens of seconds after the batch flow while injected
            // gestures still physically scroll (framebuffer moved, tree
            // frozen), so any post-batch scroll excursion makes the
            // top-anchor seek below read a stationary-but-frozen viewport
            // and its entry await starve. After await 导入 the list is
            // already at its top with the batch summary; the bottom-reach
            // proof's acceptance-grade counterpart is the reopen() snapshot
            // equality (Route B: in-chain stages are UI observation only).
            // The remaining seekBackToTop calls are no-op-at-top anchored by
            // the lag-aware stationary verdict (round 14/15) — bounded, no
            // backward movement needed.
            ui.seekBackToTop()
            // D-217: the summary counters are best-effort on-device UI
            // evidence — the instrumentation client's tree intermittently
            // blinds out this region even at the physical top (five local
            // runs observed the rendered rows while has() stayed false). The
            // acceptance-grade verification of the skip (zero economics,
            // snapshot equality) runs in reopen() against the oracle, so a
            // UI-blind miss here must not fail the chain; the attempt and
            // outcome stay in the evidence for the forensics to read.
            // D-320: the skip is the typed IMPORT_BATCH_DECISION_INCOMPLETE
            // admission (P503ImportBatchPresentation.kt:352): the target is
            // selectable but its decision is not filled in yet — the state this
            // stage completes in the second half — so the summary reads
            // 已入账 0 项 / 跳过 1 项.
            val sawSummary =
                runCatching {
                    ui.await(20000) { ui.has("最近批量结果", prefix = true) }
                    ui.nodes(ui.root()).flatMap(ui::labels).any { it.contains("已入账 0 项") && it.contains("跳过 1 项") }
                }.getOrDefault(false)
            evidence.put("batchSkipSummarySeen", sawSummary)
            // D-217: return to the review list top by scrolling back until the
            // format section (the list's fixed first viewport) is visible —
            // edge(last=false) proves a stationary viewport against list
            // markers that sit below the batch-result section this view may
            // still be showing, so it is the wrong tool here.
            ui.seekBackToTop("刷新清单")
            // Same bounded first-page jump index as the first open (D-320).
            ui.openCandidateByAmount(amount, FIRST_PAGE_JUMP_INDEX)
            // The detail screen carries no scrollable container (verified on
            // the D-216 local channel), so seek's scroll fallback can never
            // rescue a miss here; click's own await + miss-reset is the whole
            // navigation. Both options are single-entry catalogs, and the
            // completion postcondition (决策已补全) verifies both clicks took
            // effect — the acceptance-grade decision-fact check runs in
            // reopen() against the oracle.
            // D-217: both decision clicks are part of one verified unit — a
            // category click alone can complete the decision text transiently
            // (the completion line renders between the two clicks too), so
            // retrying the pair against the single postcondition is the honest
            // shape. The acceptance-grade decision-fact check runs in reopen()
            // against the oracle.
            var decisionCompleted = false
            var lastMiss: IllegalStateException? = null
            for (attempt in 0 until 3) {
                try {
                    if (attempt > 0) {
                        // A failed attempt leaves the app at an unknown
                        // screen. Re-anchor: back to the review list, then
                        // reopen the target row (content-anchored, idempotent
                        // from any list position).
                        if (ui.has("候选详情")) ui.click("返回")
                        ui.openCandidateByAmount(amount, FIRST_PAGE_JUMP_INDEX)
                    }
                    ui.click("○ " + state.getString("categoryLabel"))
                    ui.click("○ " + state.getString("accountLabel"))
                    ui.await(20000) { ui.has("决策已补全。") }
                    decisionCompleted = true
                } catch (miss: IllegalStateException) {
                    // D-217 round 21 (closure review): only a transient miss is
                    // retried. The dominant transient is `ui.await`'s own
                    // timeout ("UI condition deadline exceeded") — the old
                    // `contains("deadline")` guard threw that same message
                    // family back, so the loop only ever retried the narrow
                    // "clickable target absent" race and never an await
                    // timeout. Retry ONLY an await timeout; tick's "scale
                    // deadline exceeded" is a real phase overrun and must
                    // propagate, as must any unexpected throwable (an
                    // information-free exception included) — swallowing them
                    // here would misclassify a deadline as FAIL and hide the
                    // real cause. The last miss is rethrown if every attempt
                    // fails, so the cause survives.
                    if (miss.message?.contains("UI condition deadline exceeded") == true) {
                        lastMiss = miss
                    } else {
                        throw miss
                    }
                }
                // D-217 review: `return@repeat` only advanced to the next
                // iteration, so a success still re-clicked the completed
                // detail screen twice more (re-clicking a decided row is a
                // real hazard). Stop the retry loop the moment the
                // postcondition holds.
                if (decisionCompleted) break
            }
            if (!decisionCompleted) throw lastMiss ?: IllegalStateException("decision completion never observed")
            // The detail screen carries no scrollable container, so edge's
            // scrollable() lookup cannot run here (D-216 verified); the
            // re-open position below is found content-anchored, so no scroll
            // reset is needed before returning to the list.
            // Selection may remain after a skipped batch (the overview keeps
            // its selection set), so the final batch must be driven from this
            // detail's own toggle. D-217 round 18 (evidence31): a stale-node
            // clickNode fails silently (D-216), so the toggle is verified by
            // re-reading the detail checkbox state. D-217 round 19
            // (evidence32): the pre-toggle isChecked read itself answered from
            // the stale client tree ("already checked" on a candidate never
            // selected in the run), the guarded toggle was skipped, and the
            // final batch carried only the residual skip-batch selection
            // (IMPORT_BATCH_DECISION_INCOMPLETE again). In this chain the
            // decided candidate is never pre-selected, so toggle
            // unconditionally and verify the selection's effect.
            // D-217 round 21 (closure review): the verified toggle is wrapped
            // in a 2-attempt loop. Each attempt re-finds the checkbox node
            // FRESH — AndroidScaleUi exposes no public cache reset, and a
            // fresh lookup through findNode's cache/refresh path is the
            // available refresh — clicks it, and awaits the selection's
            // effect; on a 20s await miss the second attempt clicks
            // again, while a real phase overrun (tick's "scale deadline
            // exceeded") still propagates.
            // D-338 (run 43801673636, 2026-11-16): the postcondition is the
            // ENTRY, not the checkbox reading. The pre-D-338 loop awaited
            // `isChecked == true` — the stale-client-tree read the round-19
            // incident above already caught answering "already checked" on a
            // candidate this run never selected. 进入批量确认 renders only
            // while selectedCandidateIds is non-empty (P503ImportReview.kt:1035-1047,
            // the detail's own entry), so the entry's presence is the same
            // selection fact read at effect level, and the retry now answers
            // to that effect instead of to the tree's claim.
            // D-338 review (P0): the entry is the selection's effect, but it can already be
            // true on arrival — the first submit above selected this candidate and a completed
            // batch never clears the selection (P503Reducer.kt:3111, :2701-2705). Toggling
            // unconditionally would therefore UNSELECT it, and the stale client tree would then
            // keep reporting the vanished entry as present. So: take the effect as-is when it
            // already holds, and only toggle when it is genuinely missing (when root.refresh()
            // succeeds, a miss re-walks the tree and — rate permitting — forces a cache reset,
            // the trustworthy direction; a failed refresh simply keeps the miss a miss).
            var selected = ui.has("进入批量确认")
            // D-338 review: the P0 premise ("the entry is already there on arrival") is the one
            // fact the next run's forensics cannot otherwise recover — record which branch ran.
            evidence.put("detailSelectionTakenAsIs", selected)
            if (!selected) {
                for (attempt in 0 until 2) {
                    val checkbox = ui.nodes(ui.root()).first { it.contentDescription?.toString() == "勾选候选" }
                    ui.clickNode(checkbox)
                    selected =
                        try {
                            ui.await(20000) { ui.has("进入批量确认") }
                            true
                        } catch (miss: IllegalStateException) {
                            if (miss.message?.contains("scale deadline exceeded") == true) throw miss
                            false
                        }
                    if (selected) break
                }
            }
            evidence.put("detailSelectionEstablished", selected)
            check(selected) { "the detail's 进入批量确认 entry never surfaced (selection was never established)" }
            // D-338: the second, decision-complete submit runs HERE, on the
            // detail screen, because the product puts the entry here. Round 11's
            // park dump (run 43801673636, 43 visible texts) read the TRUE list
            // bottom — the page footer (加载更多导入候选 / 加载更多) and the
            // bottom-bar labels 首页/账户/分析 sit in the same window, so the
            // park-distortion arm is excluded — and carried no 进入批量确认
            // node: the list-side button renders off selectedCandidateIds
            // (P503ImportReviewPresentation.kt:1488-1490), and this chain's only
            // selection write point is the detail toggle above, so a list-bottom
            // leg can only ever seek an entry the chain never made. The
            // list-bottom path is retired, not repaired; the entry's home is the
            // detail screen by product design (P503ImportReview.kt:1035-1047).
            ui.click("进入批量确认")
            ui.await { ui.has("确认入账（1 项）") }
            // The first submit above ran on the decision-incomplete batch and
            // type-skipped it (已入账 0 项 / 跳过 1 项, D-320); this one carries
            // the completed decision, so its authorize is the chain's one
            // transaction-creating act.
            ui.click("授权逐项入账")
            ui.await { ui.has("导入", prefix = true) }
            // Landing gate: the authorize hop leaves ImportBatchSubmitting for
            // the overview with the import surface still mounted
            // (P503Reducer.kt:3093-3111), and that surface recomposes at its
            // TOP, where 刷新清单 is the first row. The bottom walk below needs
            // the list, not the submitting screen.
            ui.await { ui.has("刷新清单") }
            // D-338 effect witness: the retained 最近批量结果 summary. It renders
            // AFTER the candidate rows and the batch entry
            // (P503ImportReviewPresentation.kt:1493-1504), so it is never in the
            // landing viewport — the reason the first-half read above always
            // misses at this scale (batchSkipSummarySeen=false in run
            // 43801673636), and the reason D-337's list leg walked to the bottom
            // before reading it. Walk there first (the ACTION_SCROLL_TO_POSITION
            // fast path, ~6s on run 43741730054-a2), then wait for the CONTENT:
            // until this batch's items land the retained summary is still the
            // first batch's 已入账 0 项 / 跳过 1 项, so awaiting the section
            // header alone would read the stale line.
            ui.edge(last = true)
            // D-338 review (P2): only the await's own timeout is a retryable miss. A bare
            // runCatching would also swallow tick()'s "scale deadline exceeded" and relabel a
            // phase overrun as a failed booking — the same classification rule the toggle
            // loop above and the round-21 retry already follow.
            val booked =
                try {
                    ui.await(120000) { ui.nodes(ui.root()).flatMap(ui::labels).any { line -> line.contains("已入账 1 项") } }
                    true
                } catch (miss: IllegalStateException) {
                    if (miss.message?.contains("scale deadline exceeded") == true) throw miss
                    false
                }
            evidence.put("batchBookedSeen", booked)
            check(booked) { "the decided batch never booked its transaction (已入账 1 项 not observed)" }
            // D-338: hand the viewport back at the list TOP for the group leg —
            // the same closing edge D-337 gave its batch leg. The pre-D-338 tail
            // clicked 返回 to leave the detail screen; the authorize hop has
            // already left it (the landing gate above is that proof), and 返回
            // belongs to the detail and batch screens only
            // (P503ImportReview.kt:926, P503ImportBatch.kt:245), so the tail is
            // the viewport handoff plus the list-surface assertion the old effect
            // click carried.
            ui.edge(last = false)
            ui.await { ui.has("刷新清单") }
        }
        // D-337 (run 43783363684, 2026-11-16): this leg is the chain's load-bearing writer — its
        // 授权逐项入账 is the only submit that creates a transaction (AuthorizeImportBatch ->
        // import_confirmation + ledger_transaction + 2 posting). detail_decision's first submit
        // runs on a decision-incomplete batch and lands 已入账 0 项 / 跳过 1 项 (D-320); the
        // decision-complete one lands 已入账 1 项, and every downstream leg reads that
        // transaction: checkEconomicUi's 2026-01 · 交易 1 笔 / CNY 结余 / 交易详情 / 创建入口：导入创建,
        // reopen()'s assertFixture and counts, replay()'s assertEconomics. Run 43783363684
        // measured D-336's skip arm (batchConfirmationSkipped=true, PASS in 1095ms): the ledger
        // stayed empty, the overview painted 账本为空，还没有任何交易。 with no 月份： row, and
        // detail_monthly_refresh died at its 180s ceiling on the selector await. The leg stays
        // BEFORE group_disposition: the group leg submits its whole group as duplicate-marked,
        // that submission clears the selection, and 进入批量确认 stops rendering.
        // D-338 (run 43801673636, 2026-11-16) keeps that order and moves the SUBMIT into
        // detail_decision, where the product's entry lives; this stage is the submit's witness.
        // Round 11 parked at the true list bottom (the page footer 加载更多导入候选 / 加载更多 and the
        // bottom-bar labels in the same window) with no 进入批量确认 node in the 43-text dump, so the
        // list-bottom seek this stage used to open with is retired together with its D-335 park
        // probe — parkHasEntry/parkTexts could only ever re-read that absence.
        stage("batch_confirmation") {
            // D-338: the decide-then-book submit happens in the detail screen above where the
            // product puts the entry; this stage is its witness — the chain continues only when
            // the booking summary was actually observed, so a silently lost submit still fails
            // the chain here rather than surfacing as an empty ledger three stages later.
            check(evidence.getBoolean("batchBookedSeen")) { "the batch leg's booking was never observed" }
            // D-338 review (P2): this stage carries no UI work of its own, and result.py rejects
            // a stage whose elapsedMs is zero (0 < elapsedMs). The tree read below is the
            // structural lower bound that rule needs (the same TitleBar row was awaited at the
            // end of the detail leg, so its witness value is small), and its check asserts the
            // authorize hop landed on the import surface rather than staying on the submitting
            // screen — the one thing the previous leg's await does not pin by itself.
            check(ui.has("导入", prefix = true)) { "the batch leg did not land on the import surface" }
        }
        // D-217 round 12 (evidence20): the collect-only traversal stage moved
        // out of the chain into reopen(). The walk used to run BEFORE
        // group_disposition/batch_confirmation, so it collected CHAIN-TIME
        // signatures while reopen() compared them against the POST-chain
        // quiescent snapshot — a temporal mismatch that made the multiset
        // equality unsatisfiable by design. The walk now runs inside
        // reopen(), after ui.launch(), where the UI displays exactly the
        // snapshot-consistent final list.
        stage("group_disposition", 5400000) {
            // D-323 (run 43513000303 evidence): the D-322 count-driven entry renders at the TOP of
            // the review list (the affordance sits in the preamble BEFORE all candidate rows), while
            // a viewport arriving from a deeper park cannot see it. D-337 put batch_confirmation
            // directly before this stage and D-338 made that stage a UI-free witness, so the
            // viewport handoff now comes from detail_decision's own closing edge(last = false)
            // (:964): this stage starts on row 0 and the walk below is a no-op confirmation
            // rather than a climb. It stays because the entry point is the invariance that
            // matters, not the predecessor's last scroll offset.
            // seek() only moves FORWARD, and a LazyColumn row above the viewport is not in the a11y
            // tree — a plain seek could never reach the button. seekBackToTop first (the same marker
            // walk openCandidate uses, AndroidScaleUi seekBackToTop): 刷新清单 is the list's own
            // first row (TitleBar), so the backward walk's target is above-or-visible from any
            // park, and the button is then one small forward scan away.
            ui.seekBackToTop("刷新清单")
            // D-326 probe (run 43561950134): the top-anchored seek's instant view. The failure
            // forensics' texts block truncates exactly this evidence, and the a11y tree has a
            // known blind spot in this region (rendered rows while has() stayed false), so the
            // top window's full labels and the button's own presence are recorded BEFORE the
            // walk — one line settles "was the affordance in the tree at the top at all".
            evidence.put(
                "groupEntryTopWindowLabels",
                ui
                    .nodes(ui.root())
                    .flatMap(ui::labels)
                    .joinToString("|")
                    .take(4000),
            )
            evidence.put("groupEntryTopWindowHasButton", ui.has("整组标记为重复", prefix = true))
            ui.seek("整组标记为重复")
            ui.click("整组标记为重复")
            // D-328 first-window probe (run 43587072230 attempt 2): the card never landed
            // within the default 180s await — the 50,000-relation enumeration's first window
            // was still pending when the deadline fired (the P1 trace's every-7s
            // review.groupGate rows held count=value=50000 loadedGroupRows=0 for the whole
            // wait; the screenshot showed only the 正在整理重复组…… line). The await is the
            // WAIT CEILING, not the stage budget: run 43587072230's read proved the default
            // fires long before group_disposition's limit is at risk, so this run raises it
            // explicitly to 900s to MEASURE the real first-window landing time. The stage
            // budget (5,400s) stays untouched, and only this call site passes the timeout —
            // every other await keeps its default, so non-maximum rigs are unaffected. The
            // probe mounts the D-328 milestone trace and the groupCardAwaitSamples capture
            // on onPoll; the predicate is the exact pre-probe one (same text, same
            // findNode path), and the failure is rethrown as the same instance so the
            // stage's PASS/ERROR classification cannot drift.
            val probe = GroupCardAwaitProbe(ui, evidence, spec.newSessionDuplicateRelations)
            try {
                ui.await(timeout = 900_000, onPoll = probe::onPoll) {
                    ui.has(groupCardDisclosureText(spec.newSessionDuplicateRelations))
                }
                probe.end("card-opened")
            } catch (failure: Throwable) {
                val outcome =
                    when {
                        failure is IllegalStateException && failure.message == "UI condition deadline exceeded" -> "await-timeout"
                        else -> "await-error"
                    }
                probe.end(outcome)
                throw failure
            }
            // D-217: the session tag (本次会话：<inputRef>) is no longer
            // verifiable in-chain — mainInputRef used to be discovered by the
            // chain's oracle read, which this design removed. The group
            // being the main session's is asserted in reopen(), where
            // assertFixture discovers the main session from the quiescent
            // snapshot and the confirmed-relations set must equal prepared +
            // main.
            ui.edge(last = true)
            ui.seek("确认整组标记", forward = false)
            ui.click("确认整组标记")
            // D-332 (approved v2, Q1 = ③+①, Q4 = await 轮末 → 重 click → 原 await; run
            // 43663940414's refusal timeline: the click at 11:16:22 was accepted BY the
            // product's refusal gate while the enumeration round was at ~2% —
            // ordinal ~1001/50000 — the gate silently returned with no dispatch, the round
            // Completed at 11:22:18 inside the old await window, and the outcome still never
            // arrived because the refused tap is never re-sent: the missing piece was the
            // ROUND-END RE-CLICK, not time). The product side (same batch) disables the
            // footer's confirm affordance while the round is in flight and carries the hint
            // 「轮次整理中，完成后可确认」 in its contentDescription — and THAT description is
            // the round-end marker this await negates. The task book's first-choice
            // progress-line-absence predicate is VACUOUS here (implementation finding):
            // `edge(last=true)` + the reverse seek park the viewport at the card footer, the
            // LazyColumn composes only the visible window, and the progress line sits ABOVE
            // the card block — off-window nodes are not in the a11y tree (the D-330 v4
            // finding), so `!has("正在整理重复组……")` would be true-at-first-poll whether the
            // round ran or not. The footer description IS composed at this viewport (the
            // button itself is the seek/click target), carries the same
            // groupEnumerationInProgress source as the progress line (assembly
            // P503ImportReviewPresentation.kt, cleared only by the round's Completed), and its
            // disappearance IS the round end — b6 shapes skip the wait entirely (the footer
            // never disables: the round completed before this seek). The 900s ceiling is the
            // explicit round allowance inside group_disposition's 5,400s stage budget
            // (~10s consumed by the card-open await + the traversals above; the D-329 read
            // measured a healthy 50k round at 190.8s). The re-click after the wait lands on
            // the re-enabled affordance; the disposition single-flight gate and the
            // Reviewed-item re-run idempotency make a redundant re-click harmless either way.
            ui.await(timeout = 900_000) { !ui.has("轮次整理中，完成后可确认") }
            // The Q4 re-click: the refused first tap is never re-sent by the product (the
            // missing piece of run 43663940414), so the rig re-clicks once the footer has
            // re-enabled — the confirm then passes the refusal gate and authorizes the whole
            // group exactly as the D-280 §3.4 semantics intend.
            ui.click("确认整组标记")
            // D-333 deliverable 2 (rig run 43707047398: the outcome await sampled 60x with
            // text="none", pending/marked/failed all 0, firstHitMs=-1 — D-331's zero-member-row
            // collapsed card keeps the outcome rows OUT of the composed tree entirely, so the
            // probe's viewport walk can never see 待处置/已标记：/失败 lines no matter what the
            // reducer lands). Before constructing the probe, expand the member rows through the
            // D-330 view-all entry row. Its copy comes from the pure label function
            // (查看全部 N 条, N = the whole-group total — 50,000 here), and the row composes only
            // while the card is collapsed: in the expanded form the entry row is gone (the
            // collapse row leads instead), so the presence probe below is also the already-
            // expanded skip. The expanded list is needed only for the probe's viewport sampling;
            // the await predicate itself (已标记：CONFIRMED_DUPLICATE) is one character untouched,
            // and the probe's sampling logic is untouched too. The entry row may sit above the
            // parked footer viewport, so the seek walks back up to it first — the same
            // seek/click shape this file already uses.
            if (ui.has("查看全部 ", prefix = true)) {
                ui.seek("查看全部 ", forward = false)
                ui.click("查看全部 ", prefix = true)
            }
            // D-217 round 17 (evidence30): 确认整组标记 submits per-item and
            // the card itself renders each relation's outcome
            // (已标记：CONFIRMED_DUPLICATE, presentation-frozen copy). The
            // group flow never produces a batch summary — batchResult is set
            // only by the batch submission reducers — so the old
            // 最近批量结果 await waited on a text no group marking can
            // render and burned the stage budget. Verify the submission by
            // its rendered outcome; the acceptance-grade relation equality
            // runs in reopen() against the oracle.
            // D-331 deliverable 2 — the outcome-visibility probe (run
            // 43642845302's open question): this await hit its old default
            // 120s ceiling with the failure forensics' viewport parked in the
            // card's member-row region and targetHit=false, and the open
            // question was whether the 50,000 per-item outcomes render
            // FIRST-ITEM-IMMEDIATELY (a viewport problem) or in a WHOLE-LIST
            // CADENCE (a measurement problem). The probe below re-applies the
            // D-328 GroupCardAwaitProbe discipline (onPoll samples, evidence
            // keys, ULStartup round trace); the wait ceiling rises to 600s to
            // MEASURE the real outcome latency — inside group_disposition's
            // 5,400s stage budget, every other await keeps its default. The
            // predicate is the exact pre-probe one, one character untouched.
            val groupMarkProbe = GroupMarkOutcomeProbe(ui, evidence)
            try {
                ui.await(timeout = 600_000, onPoll = groupMarkProbe::onPoll) {
                    ui.has("已标记：CONFIRMED_DUPLICATE")
                }
                groupMarkProbe.end("outcome-hit")
            } catch (failure: Throwable) {
                val outcome =
                    when {
                        failure is IllegalStateException && failure.message == "UI condition deadline exceeded" -> "await-timeout"
                        else -> "await-error"
                    }
                groupMarkProbe.end(outcome)
                throw failure
            }
            evidence.put("groupMarkOutcomeSeen", true)
            // D-217 round 20 (evidence33): the card stays open — a 关闭 click on a stale node can
            // navigate clean off the review list (the run ended on the import root screen).
            evidence.put("mainGroupRelations", spec.newSessionDuplicateRelations).put("groupDispositions", spec.newSessionDuplicateRelations)
            // D-334 collapse handoff (run 43727676294): the expansion above exists only
            // for the probe's viewport sampling. Leaving it composed once stranded the
            // following leg's bottom-edge walk in the degraded gesture loop over 50k member
            // rows (that batch_confirmation leg burned its whole 180s phase budget inside
            // edge(last=true) -> scroll -> settle). D-337 moved that batch leg BEFORE this
            // stage, so no downstream leg walks the bottom any more; the collapse stays as
            // the card's restored end shape — the zero-member-row form this stage opened in
            // (D-331) — which keeps the group leg's end state identical to every pre-D-337
            // run. Restore the collapsed zero-member-row shape before the stage ends: the
            // collapse affordance is the card's leading 收起成员行 row (groupCardCollapseRowsText
            // — the exact inverse of the 查看全部 entry row, NOT the header's 收起全部比较信息
            // toggle, which folds the per-row comparison text only). The row composes only
            // while the member list is expanded, so the guarded shape mirrors the expansion
            // leg above — the presence probe is also the already-collapsed skip, and a miss
            // leaves the expanded shape with no assertion to fail here (the assertable group
            // facts live in reopen()).
            if (ui.has("收起成员行", prefix = true)) {
                ui.click("收起成员行", prefix = true)
            }
        }
        stage("detail_monthly_refresh") { checkEconomicUi() }
    }

    private fun assertEconomics() {
        // D-217: replay-only economics re-assertion. The full economics block
        // (including integrity checks) lives in reopen(); replay runs in an
        // app-stopped phase too, so these reads are fence-free.
        val snapshot = oracle.snapshot(state.getString("ledger"))
        oracle.assertFixture(snapshot, state, spec, final = true)
        assertPreparedUnchanged(snapshot)
        check(snapshot.counts.getValue("ledger_transaction") == 1L)
        check(snapshot.counts.getValue("transaction_version") == 1L)
        check(snapshot.counts.getValue("posting") == 2L)
        check(snapshot.counts.getValue("import_confirmation") == 1L)
        check(snapshot.counts.getValue("import_candidate_decision_snapshot") == 1L)
    }

    private fun checkEconomicUi() {
        ui.click("首页")
        ui.await { ui.has("月份：", prefix = true) }
        repeat(120) {
            if (ui.has("月份：2026-01")) return@repeat
            ui.click("上一月")
            ui.settle()
        }
        ui.await { ui.has("2026-01 · 交易 1 笔") }
        val amount = state.getLong("selectedAmount")
        val text = (amount / 100).toString() + "." + (amount % 100).toString().padStart(2, '0')
        check(ui.has("CNY：普通收入 0.00，净支出 " + text + "，结余 -" + text))
        val transaction = ui.nodes(ui.root()).first { node -> node.actionList.any { it.label?.toString() == "查看交易详情" } }
        ui.clickNode(transaction)
        ui.await { ui.has("交易详情") }
        check(ui.has("创建入口：导入创建"))
        check(ui.has("发生时间：2026-01-15T08:00:00+08:00"))
        ui.click("返回")
    }

    private fun reopen(): ScaleSnapshot {
        // D-337: the skip arm is gone, so this PASS now implies the batch leg ran. The gate
        // alone never proved the submission — run 43783363684 recorded PASS with an empty
        // ledger under the D-336 guard — so the counts below stay the load-bearing assertion.
        // D-338: the gate now reads the witness the detail leg wrote (batchBookedSeen — its own
        // booked summary), so a PASS here is not consent-only. The witness lives in the detail
        // leg; this line only reads its echo, and the counts below remain the acceptance-grade
        // proof.
        check(evidence.getJSONObject("stages").getJSONObject("batch_confirmation").getString("status") == "PASS")
        // D-217 (round 21 reword): the host has force-stopped the app, so
        // the heavy oracle reads below — every one taken BEFORE ui.launch()
        // — run against a database with no live writer and the D-212 wall
        // cannot occur for them. The post-launch tail read (reopenTail) is
        // different: there the app is live again, and its safety rests on
        // post-chain quiescence, not on the force-stop. This is where all
        // heavy SQL assertions live now; the in-chain stages only observed
        // the UI.
        val snapshot = oracle.snapshot(state.getString("ledger"), compareReadPath = true)
        oracle.assertFixture(snapshot, state, spec, final = true)
        assertPreparedUnchanged(snapshot)
        // D-217 review fix: measure the counters this phase owns from the
        // quiescent snapshot itself (the in-chain saf_import stage cannot read
        // the oracle across the D-212 wall and must not echo the spec).
        evidence.put("finalCandidates", snapshot.rows.size).put("finalRelations", snapshot.relations)
        // D-217 review fix (group disposition): assertFixture(final=true) above
        // discovered the main SAF session into state.mainInputRef from the
        // quiescent snapshot, so the group-disposition invariants the in-chain
        // stage could no longer check are restored here — the confirmed set is
        // exactly the prepared baseline plus the main session's whole relation
        // group, that group is disjoint from the baseline, and its size is the
        // spec's new-session relation count.
        val mainGroupIds = oracle.relationIds(state.getString("ledger"), state.getString("mainInputRef")).map { it.first }.toSet()
        val preparedRelationIds = state.getJSONArray("preparedRelationIds").let { ids -> (0 until ids.length()).map { ids.getString(it) }.toSet() }
        check(mainGroupIds.size == spec.newSessionDuplicateRelations)
        check(mainGroupIds.intersect(preparedRelationIds).isEmpty())
        check(snapshot.confirmedRelations == preparedRelationIds + mainGroupIds)
        check(snapshot.confirmedRelations.size == spec.initiallyConfirmedRelations + spec.newSessionDuplicateRelations)
        check(snapshot.counts.getValue("import_duplicate_status_history") == spec.duplicateHistoryAfterGroup)
        check(snapshot.counts.getValue("import_duplicate_review_receipt") == spec.duplicateReviewReceiptAfterGroup)
        check(snapshot.rows.count { it.status == "confirmed" } == 1)
        // D-217: the in-chain stages cannot know the row id without an oracle
        // read, so reopen derives it at the quiescent point. D-320: the confirmed
        // row is the first-page target — session-01's row at the amount the chain
        // read from the rendered row. Session-01's amounts are distinct per
        // ordinal (seed + 1 + ordinal), so session plus amount identify exactly
        // one row; the ordinal is whatever the page contained, not a fixture
        // literal.
        val confirmed = snapshot.rows.single { it.status == "confirmed" }
        check(confirmed.session == firstPageSessionInputRef())
        check(confirmed.amount == state.getLong("selectedAmount"))
        state.put("selectedId", confirmed.id)
        check(snapshot.counts.getValue("ledger_transaction") == 1L)
        check(snapshot.counts.getValue("transaction_version") == 1L)
        check(snapshot.counts.getValue("posting") == 2L)
        check(snapshot.counts.getValue("import_confirmation") == 1L)
        check(snapshot.counts.getValue("import_candidate_decision_snapshot") == 1L)
        oracle.read { database, _ ->
            database.rawQuery("SELECT account_id,amount_minor,currency_code,currency_precision FROM posting ORDER BY account_id", null).use { cursor ->
                val actual = mutableMapOf<String, Long>()
                while (cursor.moveToNext()) {
                    check(cursor.getString(2) == "CNY" && cursor.getInt(3) == 2)
                    actual[cursor.getString(0)] = cursor.getLong(1)
                }
                check(actual == mapOf(state.getString("accountId") to -state.getLong("selectedAmount"), state.getString("expenseAccountId") to state.getLong("selectedAmount")))
                check(actual.values.sum() == 0L)
            }
            database.rawQuery("PRAGMA integrity_check", null).use { check(it.moveToFirst() && it.getString(0) == "ok" && !it.moveToNext()) }
            database.rawQuery("PRAGMA foreign_key_check", null).use { check(!it.moveToFirst()) }
        }
        evidence.put("formalTransactions", 1).put("balancedPostings", 2)
        state.put("endIdentity", snapshot.identityDigest).put("endPersistence", snapshot.persistenceDigest)
        ui.launch()
        return snapshot
    }

    /**
     * D-217 round 21 (closure review): the reopen phase's two sibling
     * stages. The traversal walk used to be registered as a stage NESTED
     * inside the reopen stage's action — see the `when` dispatch comment in
     * [maximumScalePhase] for why that could never validate — so it now
     * registers only after the reopen stage has fully completed (PASS
     * status and elapsedMs written, phase deadline restored). The explicit
     * `if (phase == "reopen")` gate keeps the walk exactly-once even if a
     * future caller re-enters this function from another phase.
     */
    private fun reopenPhase() {
        val snapshot = stage("reopen", 1800000) { reopen() }
        if (phase == "reopen") {
            stage("traversal", 14400000) {
                traversalWalk(snapshot)
                reopenTail(snapshot)
            }
        }
    }

    /**
     * D-217 round 12 (evidence20): the traversal walk moved out of the
     * chain into the reopen phase. In the chain it ran BEFORE
     * group_disposition and batch_confirmation, so the signatures it
     * collected reflected the CHAIN-TIME state (main-session rows still
     * DEFERRED, the unique row unconfirmed), while the multiset comparison
     * below checked them against the POST-chain quiescent snapshot (those
     * same rows CONFIRMED_DUPLICATE, the unique row confirmed) — roughly 21
     * signatures drifted, so the equality was unsatisfiable BY DESIGN. The
     * walk runs after ui.launch() (the reopen stage's tail): the relaunched
     * UI displays exactly the snapshot-consistent final list, so both sides
     * of the comparison observe the SAME state.
     *
     * State-silence invariant: the relaunch plus the collect-only walk
     * (scroll gestures only, no clicks) must not write to the database
     * between the snapshot (read with the app stopped) and this walk. The
     * traversal stage's own tail ([reopenTail]) re-reads the oracle after
     * the walk, so a stray write fails in the same phase, and final-reopen
     * re-runs [reopen] on a fresh snapshot — if the relaunch or walk wrote
     * anything, its assertions would fail there too. The walk is proven
     * state-silent on every run.
     */
    private fun traversalWalk(snapshot: ScaleSnapshot) {
        // The app relaunched to the home screen; the drawer's 导入账单 row opens
        // the import screen whose review list collectTraversal walks (the same
        // drawer navigation the chain's saf_import stage and the reopen tail
        // use, and the only in-app route since P8-00 removed the bottom bar's
        // import tab).
        ui.openImportViaDrawer()
        ui.await { ui.has("支付宝账单（CSV）") }
        // collectTraversal seek-backs to the 刷新清单 anchor itself
        // before walking the whole list exactly once; it requires the
        // unique-session first row (its amount is derived from the
        // manifest seed) to appear, keeping the traversal's coverage
        // mandate without a live-app oracle read.
        val collected = ui.collectTraversal(uniqueSessionFirstAmount(), spec.finalCandidates)
        state.put("observedSignatures", JSONArray(collected))
        evidence
            .put("observedCandidates", collected.size)
            .put("firstLastObserved", true)
            .put("uiIdentityScope", "projected-class-groups-query-order")
        // Traversal alignment: the collected signatures must cover
        // exactly the expected multiset of display rows (every row
        // seen, no duplicates). collectTraversal preserves
        // multiplicity, so both sides are genuine multisets and the
        // sizes are directly comparable. The snapshot was read with
        // the app stopped and the walk writes nothing (invariant
        // above), so the comparison is same-state by construction.
        val expected = snapshot.displayRows.map { it.signature }
        check(collected.sorted() == expected.sorted()) { "traversal coverage mismatch (collected=${collected.size} expected=${expected.size})" }
    }

    /**
     * The post-launch tail shared by the reopen and final-reopen phases:
     * re-reads the oracle after the traversal walk (or, in final-reopen,
     * after the relaunch alone) and fails on any stray write. D-217 round
     * 21 (closure review): the app is LIVE here — ui.launch() ran — so this
     * read's safety rests on post-chain quiescence, not on the force-stop.
     */
    private fun reopenTail(snapshot: ScaleSnapshot) {
        checkEconomicUi()
        ui.openImportViaDrawer()
        ui.await { ui.has("支付宝账单（CSV）") }
        val reopened = oracle.snapshot(state.getString("ledger"))
        check(reopened == snapshot)
    }

    /**
     * The final-reopen phase: the shared quiescent assertions plus the tail
     * inside the final_reopen stage, with NO traversal stage — the walk ran
     * exactly once, in the reopen phase (D-217 round 21: this branch never
     * touches the "reopen"/"traversal" registrations, so the "stage must
     * not be retried" guard never fires).
     */
    private fun finalReopen() {
        reopenTail(reopen())
    }

    private fun replay() {
        val before = oracle.snapshot(state.getString("ledger"))
        val saved =
            oracle.read { database, _ ->
                database
                    .rawQuery(
                        "SELECT d.request_id,d.candidate_id,d.expected_content_hash,d.explicit_confirmed_at,d.category_id,d.funding_account_id,r.source_id,r.evidence_id,r.confirmation_id,r.transaction_id FROM import_candidate_decision_snapshot d JOIN import_receipt r ON r.ledger_id=d.ledger_id AND r.request_id=d.request_id WHERE d.decision='confirm'",
                        null,
                    ).use { cursor ->
                        check(cursor.moveToFirst())
                        val fields = (0..9).map { if (cursor.isNull(it)) null else cursor.getString(it) }
                        check(!cursor.moveToNext())
                        fields
                    }
            }
        val graph = openGraph()
        try {
            val result =
                checkNotNull(checkNotNull(graph.facade.importConfirmUseCases).invoke()?.ordinaryFlow).execute(
                    ImportCandidateConfirmRequest(
                        ImportRequestIdentity(graph.facade.ledgerId, ImportRequestId(checkNotNull(saved[0]))),
                        ImportCandidateId(checkNotNull(saved[1])),
                        checkNotNull(saved[2]),
                        saved[3],
                        ImportConfirmDecisionFields.OrdinaryFlow(CategoryId(checkNotNull(saved[4])), AccountId(checkNotNull(saved[5]))),
                    ),
                )
            check(result is ImportCandidateDecisionResult.NoChange && result.reasonCode == "equivalent_replay")
            val receipt = result.receipt
            check(listOf(receipt.requestId.value, receipt.candidateId.value, receipt.sourceId?.value, receipt.evidenceId?.value, receipt.confirmationId?.value, receipt.transactionId?.value) == listOf(saved[0], saved[1], saved[6], saved[7], saved[8], saved[9]))
        } finally {
            graph.close()
        }
        check(oracle.snapshot(state.getString("ledger")) == before)
        assertEconomics()
    }
}
