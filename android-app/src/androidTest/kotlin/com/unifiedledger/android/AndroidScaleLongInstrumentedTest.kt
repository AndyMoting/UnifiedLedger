package com.unifiedledger.android

import android.content.ContentValues
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.AtomicFile
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
                "reopen" -> stage("reopen") { reopen() }
                "replay" -> stage("same_request_replay") { replay() }
                "final-reopen" -> stage("final_reopen") { reopen() }
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

    private fun stage(
        name: String,
        limitMs: Long = 180000,
        action: () -> Unit,
    ) {
        val stages = evidence.getJSONObject("stages")
        check(!stages.has(name)) { "stage must not be retried" }
        val started = SystemClock.elapsedRealtime()
        phaseDeadline = minOf(deadline, started + limitMs)
        stages.put(name, JSONObject().put("status", "NOT_RUN").put("startedMs", started))
        evidence.put("activeDeadlineElapsedMs", phaseDeadline)
        save()
        try {
            tick()
            action()
            tick()
            stages.getJSONObject(name).put("status", "PASS")
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
            phaseDeadline = Long.MAX_VALUE
            evidence.remove("activeDeadlineElapsedMs")
            save()
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

    /** The unique-rows session: the last one registered during preparation. */
    private fun uniqueSessionInputRef(): String = state.getJSONArray("sessions").getJSONObject(spec.initialSessions).getString("inputRef")

    /**
     * D-217 review fix: the unique-rows session's ordinal-0 amount. Its rows
     * start after the shared range ([AndroidScaleFixtureSpec.sessionAmountOffset]),
     * so the amount is seed + 1 + rowsPerSession — disjoint from every shared
     * session's seed+1..seed+rowsPerSession range. The old `seed + 1` was the
     * first shared session's first row, not the unique one.
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
            check(ui.has("账本为空，还没有任何交易。"))
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
        // verbatim to reopen(), which runs while the app is stopped.
        stage("saf_import", 1800000) {
            ui.click("导入")
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
        // budget still governs the local run). The target row is the unique
        // session's first row: its amount is derived from the spec, and the
        // unique session is the only one holding this amount, so the UI
        // selection stays spec-derived without an oracle read.
        stage("detail_decision", if (spec.profile == "maximum") 180000L else 600000L) {
            // D-217 review fix: the unique-rows session's ordinal-0 amount is
            // seed + 1 + its sessionAmountOffset (== rowsPerSession); the old
            // `seed + 1` targeted the FIRST shared session's first row, which
            // has multiplicity 6 in the final fixture and can never open the
            // unique row the decision must confirm.
            val amount = uniqueSessionFirstAmount()
            state.put("selectedAmount", amount)
            // D-217 round 7: the target's candidate index within the review list
            // is rowsPerSession — group 0 leads with session-01's rowsPerSession
            // never-subject rows and the unique session is imported last within
            // prepare — so the spec field is the jump index the
            // ACTION_SCROLL_TO_POSITION accelerator consumes (never a hardcoded
            // profile literal).
            ui.openCandidateByAmount(amount, spec.rowsPerSession)
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
            val sawSummary = runCatching {
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
            // Same spec-derived jump index as the first open (round 7).
            ui.openCandidateByAmount(amount, spec.rowsPerSession)
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
                        ui.openCandidateByAmount(amount, spec.rowsPerSession)
                    }
                    ui.click("○ " + state.getString("categoryLabel"))
                    ui.click("○ " + state.getString("accountLabel"))
                    ui.await(20000) { ui.has("决策已补全。") }
                    decisionCompleted = true
                } catch (miss: IllegalStateException) {
                    // D-217 review: only a transient click/navigation miss is
                    // retried. A deadline overrun (tick's "scale deadline
                    // exceeded" or await's "UI condition deadline exceeded",
                    // both flagged by the stage classifier's `contains("deadline")`
                    // rule) and any unexpected throwable must propagate —
                    // swallowing them here misclassified a deadline as FAIL and
                    // hid the real cause. An information-free exception is also
                    // treated as unexpected and propagates. The last miss is
                    // rethrown if every attempt fails, so the cause survives.
                    val overrun = miss.message?.contains("deadline") ?: true
                    if (overrun) throw miss
                    lastMiss = miss
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
            // unconditionally and verify the checked postcondition.
            val checkbox = ui.nodes(ui.root()).first { it.contentDescription?.toString() == "勾选候选" }
            ui.clickNode(checkbox)
            ui.await(20000) {
                ui.nodes(ui.root()).firstOrNull { it.contentDescription?.toString() == "勾选候选" }?.isChecked == true
            }
            // D-217 round 13 (evidence22): a plain 返回 click can fail
            // silently on a stale detail-screen node — the next stage then
            // starts on 候选详情 and its seek dies with "scroll container
            // absent" (226ms). clickUntil verifies the effect: the review
            // list (刷新清单) must be visible before the stage ends.
            ui.clickUntil("返回", "刷新清单")
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
            ui.seek("整组标记为重复")
            ui.click("整组标记为重复")
            ui.await { ui.has("将逐条提交人工审核判定为重复，每条独立生效；某一条失败不影响其余各条。共 " + spec.newSessionDuplicateRelations + " 条。") }
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
            // D-217 round 17 (evidence30): 确认整组标记 submits per-item and
            // the card itself renders each relation's outcome
            // (已标记：CONFIRMED_DUPLICATE, presentation-frozen copy). The
            // group flow never produces a batch summary — batchResult is set
            // only by the batch submission reducers — so the old
            // 最近批量结果 await waited on a text no group marking can
            // render and burned the stage budget. Verify the submission by
            // its rendered outcome; the acceptance-grade relation equality
            // runs in reopen() against the oracle.
            ui.await(120000) { ui.has("已标记：CONFIRMED_DUPLICATE") }
            evidence.put("groupMarkOutcomeSeen", true)
            ui.click("关闭")
            evidence.put("mainGroupRelations", spec.newSessionDuplicateRelations).put("groupDispositions", spec.newSessionDuplicateRelations)
        }
        stage("batch_confirmation") {
            ui.edge(last = true)
            ui.seek("进入批量确认", forward = false)
            ui.click("进入批量确认", prefix = true)
            check(ui.has("确认入账（1 项）"))
            ui.click("授权逐项入账")
            ui.await { ui.has("导入", prefix = true) }
            // D-217 round 17 (evidence30): no bottom excursion here — after
            // await 导入 the list is at its top where 最近批量结果 renders,
            // and a scroll excursion leaves the viewport away from the
            // awaited text (same round-16 rationale; the bottom-reach proof
            // is reopen()'s snapshot equality).
            ui.await { ui.has("最近批量结果", prefix = true) }
            check(ui.nodes(ui.root()).flatMap(ui::labels).any { it.contains("已入账 1 项") && it.contains("拒绝 0 项") && it.contains("未知 0 项") })
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

    private fun reopen() {
        check(evidence.getJSONObject("stages").getJSONObject("batch_confirmation").getString("status") == "PASS")
        // D-217: the host has force-stopped the app, so every oracle read
        // below runs against a database with no live writer — the D-212 wall
        // cannot occur. This is where all heavy SQL assertions live now; the
        // in-chain stages only observed the UI.
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
        // read, so reopen derives it at the quiescent point: the confirmed row
        // must be the unique-session first row, whose amount the chain already
        // recorded from the manifest seed.
        val confirmed = snapshot.rows.single { it.status == "confirmed" }
        check(confirmed.session == uniqueSessionInputRef() && confirmed.ordinal == 0)
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
        // D-217 round 12 (evidence20): the traversal walk moved here from the
        // chain. In the chain it ran BEFORE group_disposition and
        // batch_confirmation, so the signatures it collected reflected the
        // CHAIN-TIME state (main-session rows still DEFERRED, the unique row
        // unconfirmed), while the multiset comparison below checked them
        // against the POST-chain quiescent snapshot (those same rows
        // CONFIRMED_DUPLICATE, the unique row confirmed) — roughly 21
        // signatures drifted, so the equality was unsatisfiable BY DESIGN.
        // The walk now runs after ui.launch(): the relaunched UI displays
        // exactly the snapshot-consistent final list, so both sides of the
        // comparison observe the SAME state.
        //
        // State-silence invariant: the relaunch plus the collect-only walk
        // (scroll gestures only, no clicks) must not write to the database
        // between the snapshot above and this walk. final-reopen re-runs
        // reopen() on a fresh snapshot — if the relaunch or walk wrote
        // anything, its assertions would fail; and on this phase the tail
        // check below (reopened == snapshot) re-reads the oracle after the
        // walk, so a stray write fails here too. The walk is proven
        // state-silent on every run.
        //
        // reopen() executes for BOTH the reopen and final-reopen phases, and
        // stage() throws "stage must not be retried" on the second
        // execution — the phase gate makes the walk run EXACTLY ONCE, in the
        // reopen phase.
        if (phase == "reopen") {
            stage("traversal", 14400000) {
                // The app relaunched to the home screen; 导入 opens the import
                // screen whose review list collectTraversal walks (the same
                // navigation the chain's saf_import stage and this phase's
                // tail use).
                ui.click("导入")
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
        }
        checkEconomicUi()
        ui.click("导入")
        ui.await { ui.has("支付宝账单（CSV）") }
        val reopened = oracle.snapshot(state.getString("ledger"))
        check(reopened == snapshot)
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
