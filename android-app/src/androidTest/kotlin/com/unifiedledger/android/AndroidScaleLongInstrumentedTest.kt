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

    private fun assertPreparedUnchanged(snapshot: ScaleSnapshot) {
        val sessions = preparedSessions()
        check(snapshot.copy(rows = snapshot.rows.filter { it.session in sessions }).identityDigest == state.getString("preparedIdentity"))
        check(oracle.relationshipBaseline(state.getString("ledger"), sessions) == state.getString("preparedRelationships"))
        val ids = state.getJSONArray("preparedRelationIds")
        check((0 until ids.length()).all { ids.getString(it) in snapshot.confirmedRelations })
    }

    private fun chain() {
        check(evidence.getJSONObject("stages").getJSONObject("preparation").getString("status") == "PASS")
        val ledger = state.getString("ledger")
        stage("coldstart") {
            coldstartLaunch()
            check(ui.has("账本为空，还没有任何交易。"))
            oracle.zeroEconomics(oracle.snapshot(ledger))
        }
        stage("saf_import", 1800000) {
            ui.click("导入")
            ui.selectSafFixture(spec.mainSessionFileName)
            ui.await(1800000) { ui.has("接治完成：新增 " + spec.mainSessionRows + "，等价重放 0，解析拒绝 0，接治拒绝 0。") }
            ui.click("刷新清单")
            ui.settle()
            val imported = oracle.snapshot(ledger, compareReadPath = true)
            oracle.assertFixture(imported, state, spec, final = true)
            assertPreparedUnchanged(imported)
            check(imported.confirmedRelations.size == spec.initiallyConfirmedRelations)
            oracle.zeroEconomics(imported)
            state.put("finalIdentity", imported.identityDigest).put("finalRelations", imported.relationDigest)
            evidence.put("finalCandidates", imported.rows.size).put("finalRelations", imported.relations)
        }
        // D-216: the local-small profile navigates without the indexed jump
        // (the client never exposes it here), so the deterministic scan is the
        // whole mechanism and needs more than the 180s the cloud contract caps
        // detail_decision at (result.py keeps that cap for maximum; the outer
        // budget still governs the local run).
        stage("detail_decision", if (spec.profile == "maximum") 180000L else 600000L) {
            val imported = oracle.snapshot(ledger)
            val uniqueSession = state.getJSONArray("sessions").getJSONObject(spec.initialSessions).getString("inputRef")
            val selected = imported.rows.first { it.session == uniqueSession && it.ordinal == 0 }
            state.put("selectedId", selected.id).put("selectedAmount", selected.amount)
            ui.openCandidate(selected, imported.displayRows)
            check(ui.has("决策未补全，尚不可提交确认。"))
            // The checkbox click must be verified by its effect: a click on a
            // stale client-cache node fails silently (D-216 local finding), and
            // the screen carries no scrollable container for seek's fallback.
            ui.clickUntil("勾选候选", "进入批量确认")
            ui.click("进入批量确认")
            ui.click("授权逐项入账")
            ui.await { ui.has("导入", prefix = true) }
            ui.edge(last = true)
            ui.await { ui.has("最近批量结果", prefix = true) }
            check(ui.nodes(ui.root()).flatMap(ui::labels).any { it.contains("已入账 0 项") && it.contains("跳过 1 项") })
            val rejected = oracle.snapshot(ledger)
            check(rejected.counts == imported.counts && rejected.identityDigest == imported.identityDigest)
            oracle.zeroEconomics(rejected)
            ui.edge(last = false)
            ui.openCandidate(selected, imported.displayRows)
            // The detail screen carries no scrollable container (verified on
            // the D-216 local channel), so seek's scroll fallback can never
            // rescue a miss here; click's own await + miss-reset is the whole
            // navigation. Both options are single-entry catalogs.
            ui.click("○ " + state.getString("categoryLabel"))
            ui.click("○ " + state.getString("accountLabel"))
            ui.await { ui.has("决策已补全。") }
            ui.edge(last = false)
            // Selection may remain after a skipped batch; only toggle when actually unchecked.
            val checkbox = ui.nodes(ui.root()).first { it.contentDescription?.toString() == "勾选候选" }
            if (!checkbox.isChecked) ui.clickNode(checkbox)
            ui.click("返回")
        }
        stage("traversal", 14400000) {
            val before = oracle.snapshot(ledger, compareReadPath = true)
            oracle.assertFixture(before, state, spec, final = true)
            check(before.identityDigest == state.getString("finalIdentity"))
            val observed = ui.traverse(before.displayRows)
            val after = oracle.snapshot(ledger)
            check(after == before)
            evidence
                .put("observedCandidates", observed)
                .put("firstLastObserved", true)
                .put("uiIdentityScope", "projected-sequence-multiplicity-order")
        }
        stage("group_disposition", 5400000) {
            val before = oracle.snapshot(ledger)
            val group = oracle.relationIds(ledger, state.getString("mainInputRef")).map { it.first }.toSet()
            check(group.size == spec.newSessionDuplicateRelations && group.intersect(before.confirmedRelations).isEmpty())
            ui.seek("整组标记为重复")
            ui.click("整组标记为重复")
            ui.await { ui.has("将逐条提交人工审核判定为重复，每条独立生效；某一条失败不影响其余各条。共 " + spec.newSessionDuplicateRelations + " 条。") }
            check(ui.has("本次会话：" + state.getString("mainInputRef")))
            ui.edge(last = true)
            ui.seek("确认整组标记", forward = false)
            ui.click("确认整组标记")
            ui.await(5400000) {
                oracle.read { database, _ ->
                    database.rawQuery("SELECT COUNT(*) FROM import_duplicate_review_receipt", null).use {
                        check(it.moveToFirst())
                        it.getLong(0) == spec.duplicateReviewReceiptAfterGroup
                    }
                }
            }
            val after = oracle.snapshot(ledger, compareReadPath = true)
            check(after.identityDigest == before.identityDigest && after.relationDigest == before.relationDigest)
            check(after.confirmedRelations == before.confirmedRelations + group)
            check(after.counts.getValue("import_duplicate_status_history") == spec.duplicateHistoryAfterGroup)
            assertPreparedUnchanged(after)
            oracle.zeroEconomics(after)
            evidence.put("mainGroupRelations", spec.newSessionDuplicateRelations).put("groupDispositions", spec.newSessionDuplicateRelations)
        }
        stage("batch_confirmation") {
            ui.edge(last = true)
            ui.seek("进入批量确认", forward = false)
            ui.click("进入批量确认", prefix = true)
            check(ui.has("确认入账（1 项）"))
            oracle.zeroEconomics(oracle.snapshot(ledger))
            ui.click("授权逐项入账")
            ui.await { ui.has("导入", prefix = true) }
            ui.edge(last = true)
            ui.await { ui.has("最近批量结果", prefix = true) }
            check(ui.nodes(ui.root()).flatMap(ui::labels).any { it.contains("已入账 1 项") && it.contains("拒绝 0 项") && it.contains("未知 0 项") })
            assertEconomics()
        }
        stage("detail_monthly_refresh") { checkEconomicUi() }
        val ended = oracle.snapshot(ledger)
        state.put("endIdentity", ended.identityDigest).put("endPersistence", ended.persistenceDigest)
    }

    private fun assertEconomics() {
        val snapshot = oracle.snapshot(state.getString("ledger"))
        oracle.assertFixture(snapshot, state, spec, final = true)
        assertPreparedUnchanged(snapshot)
        val prepared = state.getJSONArray("preparedRelationIds").let { ids -> (0 until ids.length()).map { ids.getString(it) }.toSet() }
        val main = oracle.relationIds(state.getString("ledger"), state.getString("mainInputRef")).map { it.first }.toSet()
        check(snapshot.confirmedRelations == prepared + main)
        check(snapshot.counts.getValue("import_duplicate_status_history") == spec.duplicateHistoryAfterGroup)
        check(snapshot.rows.count { it.status == "confirmed" } == 1)
        check(snapshot.rows.single { it.status == "confirmed" }.id == state.getString("selectedId"))
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
        val snapshot = oracle.snapshot(state.getString("ledger"), compareReadPath = true)
        check(snapshot.identityDigest == state.getString("endIdentity"))
        check(snapshot.persistenceDigest == state.getString("endPersistence"))
        assertEconomics()
        ui.launch()
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
