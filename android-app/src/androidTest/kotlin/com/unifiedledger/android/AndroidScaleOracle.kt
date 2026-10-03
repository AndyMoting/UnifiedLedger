package com.unifiedledger.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import com.unifiedledger.application.ImportReviewRowsResult
import com.unifiedledger.application.QueryImportReviewRows
import com.unifiedledger.data.SqlDelightImportReviewReadAdapter
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.ui.ledgerStorageLayout
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal data class ScaleRow(
    val id: String,
    val source: String,
    val session: String,
    val ordinal: Int,
    val amount: Long,
    val hash: String,
    val status: String,
    val duplicate: String?,
) {
    val group: Int
        get() =
            when {
                status == "confirmed" || status == "rejected" -> 5
                status == "incomplete" -> 4
                duplicate == "CONFIRMED_DUPLICATE" -> 3
                duplicate == "DEFERRED" -> 1
                duplicate == "CONFIRMED_DISTINCT" || duplicate == "DISMISSED_LOOKALIKE" -> 2
                else -> 0
            }
    val amountText: String get() = "${amount / 100}.${(amount % 100).toString().padStart(2, '0')} CNY"
    val metaText: String get() = "类型 ordinary_flow；发生 2026-01-15T08:00:00+08:00；方向 out；状态 settled；重复 ${duplicate ?: "无"}"
    val signature: String get() = "$amountText|$metaText"
}

internal data class ScaleSnapshot(
    val generation: String,
    val rows: List<ScaleRow>,
    val relations: Int,
    val relationDigest: String,
    val relationStateDigest: String,
    val confirmedRelations: Set<String>,
    val counts: Map<String, Long>,
    val persistenceDigest: String,
) {
    val displayRows: List<ScaleRow> get() = rows.sortedWith(compareBy<ScaleRow> { it.group }.thenBy { it.id })
    val identityDigest: String get() = scaleDigest(rows.joinToString("\n") { "${it.id}|${it.source}|${it.session}|${it.ordinal}|${it.amount}|${it.hash}" }.toByteArray())
}

internal fun scaleDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Observation never runs startup/recovery or creates a DB. */
internal class AndroidScaleOracle(
    private val context: Context,
    private val tick: () -> Unit,
) {
    private val layout = ledgerStorageLayout(AndroidLedgerFileSystem(), androidStableStoragePaths(context.getDatabasePath("ledger.db")).first)

    /**
     * One read-only observation of the live ledger, with the D-209 bounded
     * SQLITE_BUSY retry. Run 37136633556 reached the maximum chain's deepest
     * point (coldstart PASS 26.5s, `saf_import` 342s) and then
     * `assertFixture` threw `SQLiteDatabaseLockedException` here: this read
     * opens a fresh read-only connection per observation with no busy retry,
     * while the live app process still held write transactions for its
     * post-import flush, so the default ~2.5s busy timeout expired. The chain
     * design intends concurrent observation of a live app, so a lock on a
     * read-only observation is transient contention and retrying is safe:
     * every attempt re-reads the active pointer, re-validates the
     * generation/journal invariants, and re-runs the full observation, so a
     * retried read satisfies exactly the same checks as a first-attempt one.
     * The retries are bounded independently of deadlines
     * ([AndroidScaleOracleRetry.LOCK_RETRY_MAX_ATTEMPTS]); the stage deadline
     * stays the hard bound because [tick] runs after every sleep (never
     * during) and its usual deadline failure then surfaces unmasked. Non-lock
     * failures are never retried and rethrow immediately; after exhausting
     * the attempts the last lock exception is rethrown.
     */
    fun <T> read(action: (SQLiteDatabase, String) -> T): T {
        tick()
        var retry = 0
        while (true) {
            try {
                return attemptRead(action)
            } catch (failure: Throwable) {
                retry++
                if (retry < AndroidScaleOracleRetry.LOCK_RETRY_MAX_ATTEMPTS && AndroidScaleOracleRetry.isLockRetryable(failure)) {
                    SystemClock.sleep(AndroidScaleOracleRetry.lockRetryBackoffMs(retry))
                    tick()
                } else {
                    throw failure
                }
            }
        }
    }

    /** One full observation attempt: pointer/journal checks, open, read, re-check. */
    private fun <T> attemptRead(action: (SQLiteDatabase, String) -> T): T {
        val pointer = File(layout.activePointerFile)
        check(pointer.isFile && pointer.length() in 5..32) { "active pointer missing/invalid" }
        val original = pointer.readBytes()
        val name = original.toString(Charsets.UTF_8)
        check(Regex("gen-[1-9][0-9]*").matches(name)) { "invalid active generation" }
        check(!File(layout.switchJournalFile).exists()) { "unexpected switch journal" }
        val number = name.removePrefix("gen-").toInt()
        val base = File(layout.generationsDirectory).canonicalFile.toPath()
        val directory = File(layout.generationDirectory(number)).canonicalFile
        check(directory.toPath().startsWith(base) && directory.toPath() != base)
        val main = File(layout.mainFile(directory.path))
        check(main.isFile && main.length() > 0 && main.canonicalFile.parentFile == directory) { "active database missing/empty/outside generation" }
        return SQLiteDatabase
            .openDatabase(main.path, null, SQLiteDatabase.OPEN_READONLY) {
                error("database corruption; preserve file")
            }.use { database ->
                check(database.isReadOnly)
                database.beginTransactionReadOnly()
                try {
                    val result = action(database, name)
                    tick()
                    check(original.contentEquals(pointer.readBytes()) && !File(layout.switchJournalFile).exists()) { "generation changed during observation" }
                    database.setTransactionSuccessful()
                    result
                } finally {
                    database.endTransaction()
                }
            }
    }

    fun snapshot(
        ledger: String,
        compareReadPath: Boolean = false,
    ): ScaleSnapshot =
        read { database, generation ->
            val duplicateStates = HashMap<String, String>()
            val confirmed = HashSet<String>()
            val immutable = MessageDigest.getInstance("SHA-256")
            val states = MessageDigest.getInstance("SHA-256")
            var relations = 0
            val priorities = listOf("CONFIRMED_DUPLICATE", "DEFERRED", "CONFIRMED_DISTINCT", "DISMISSED_LOOKALIKE", "REJECTED")
            database
                .rawQuery(
                    """
                    SELECT d.candidate_id,d.subject_source_id,d.possible_existing_source_id,d.comparison_fingerprint,h.status
                    FROM import_duplicate_candidate d JOIN import_duplicate_status_history h
                      ON h.ledger_id=d.ledger_id AND h.candidate_id=d.candidate_id
                      AND h.sequence=(SELECT MAX(sequence) FROM import_duplicate_status_history x
                                      WHERE x.ledger_id=d.ledger_id AND x.candidate_id=d.candidate_id)
                    WHERE d.ledger_id=? ORDER BY d.candidate_id
                    """.trimIndent(),
                    arrayOf(ledger),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        if (relations % 1000 == 0) tick()
                        relations++
                        val id = cursor.getString(0)
                        val source = cursor.getString(1)
                        val status = cursor.getString(4)
                        check(status in priorities)
                        val previous = duplicateStates[source]
                        if (previous == null || priorities.indexOf(status) < priorities.indexOf(previous)) duplicateStates[source] = status
                        if (status == "CONFIRMED_DUPLICATE") confirmed += id
                        immutable.update("$id|$source|${cursor.getString(2)}|${cursor.getString(3)}\n".toByteArray())
                        states.update("$id|$status\n".toByteArray())
                    }
                }
            val rows = ArrayList<ScaleRow>()
            database
                .rawQuery(
                    """
                    SELECT c.candidate_id,s.source_id,s.input_ref,s.record_ordinal,s.amount_minor,s.content_hash,h.status,
                           s.record_kind,s.contract_version,s.completeness,s.currency_code,s.currency_precision,
                           s.occurred_at,s.direction_token,s.status_token,s.funding_state,c.candidate_kind
                    FROM import_candidate c JOIN import_source_record s ON s.ledger_id=c.ledger_id AND s.source_id=c.source_id
                    JOIN import_candidate_status_history h ON h.ledger_id=c.ledger_id AND h.candidate_id=c.candidate_id
                      AND h.sequence=(SELECT MAX(sequence) FROM import_candidate_status_history x
                                      WHERE x.ledger_id=c.ledger_id AND x.candidate_id=c.candidate_id)
                    WHERE c.ledger_id=? ORDER BY c.candidate_id
                    """.trimIndent(),
                    arrayOf(ledger),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        if (rows.size % 1000 == 0) tick()
                        check((7..16).map { cursor.getString(it) } == listOf("ordinary_flow_source", "1", "valid_complete", "CNY", "2", "2026-01-15T08:00:00+08:00", "out", "settled", "SETTLED", "ordinary_flow"))
                        rows += ScaleRow(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getInt(3), cursor.getLong(4), cursor.getString(5), cursor.getString(6), duplicateStates[cursor.getString(1)])
                    }
                }
            val tables = listOf("import_source_record", "import_candidate", "import_request", "import_candidate_status_history", "import_candidate_decision_snapshot", "import_confirmation", "import_receipt", "ledger_transaction", "transaction_version", "posting", "import_duplicate_candidate", "import_duplicate_status_history", "import_duplicate_review_request", "import_duplicate_review_snapshot", "import_duplicate_review_receipt")
            val counts =
                tables.associateWith { table ->
                    database.rawQuery("SELECT COUNT(*) FROM $table", null).use {
                        check(it.moveToFirst())
                        it.getLong(0)
                    }
                }
            check(counts.getValue("import_candidate") == rows.size.toLong())
            check(counts.getValue("import_source_record") == rows.size.toLong())
            check(counts.getValue("import_duplicate_candidate") == relations.toLong())
            if (compareReadPath) {
                val adapter = SqlDelightImportReviewReadAdapter(LedgerDatabase(AndroidScaleReadDriver(database)))
                val actual = QueryImportReviewRows(adapter).query(LedgerId(ledger))
                check(actual is ImportReviewRowsResult.Rows) { "production review read unavailable" }
                check(actual.rows.size == rows.size)
                actual.rows.zip(rows).forEach { (actualRow, expected) ->
                    check(actualRow.candidateId.value == expected.id && actualRow.sourceInputRef == expected.session)
                    check(actualRow.amountMinor == expected.amount && actualRow.contentHash == expected.hash)
                    check(actualRow.candidateStatus == expected.status && actualRow.duplicateStatus?.name == expected.duplicate)
                    check(actualRow.candidateKind == "ordinary_flow" && actualRow.currencyCode == "CNY" && actualRow.currencyPrecision == 2)
                    check(actualRow.occurredAt == "2026-01-15T08:00:00+08:00" && actualRow.directionToken == "out" && actualRow.statusToken == "settled")
                    check(actualRow.fundingState.name == "SETTLED" && actualRow.completeness.name == "VALID_COMPLETE")
                    check(actualRow.requiresConfirmation && actualRow.confidence == "1.00")
                }
            }
            val persisted =
                digestQueries(
                    database,
                    tables.map { "SELECT * FROM $it ORDER BY 1,2" } +
                        listOf(
                            "SELECT * FROM posting_set ORDER BY 1,2",
                            "SELECT * FROM ledger_transaction_current_version ORDER BY 1,2",
                        ),
                )
            ScaleSnapshot(generation, rows, relations, immutable.digest().hex(), states.digest().hex(), confirmed, counts, persisted)
        }

    fun assertFixture(
        snapshot: ScaleSnapshot,
        state: JSONObject,
        final: Boolean,
    ) {
        check(snapshot.generation == state.getString("generation"))
        val sessions = state.getJSONArray("sessions")
        val bySession = snapshot.rows.groupBy { it.session }
        check(bySession.size == sessions.length() + if (final) 1 else 0)
        check(sessions.length() == 6)
        val known = HashSet<String>()
        for (index in 0 until sessions.length()) {
            val session = sessions.getJSONObject(index)
            val inputRef = session.getString("inputRef")
            known += inputRef
            val actual = bySession.getValue(inputRef).sortedBy { it.ordinal }
            val ids = session.getJSONArray("candidateIds")
            val count = if (index < 5) 10000 else 1000
            check(actual.size == count && ids.length() == count)
            actual.forEachIndexed { ordinal, row ->
                check(row.ordinal == ordinal && row.id == ids.getString(ordinal))
                check(row.amount == state.getLong("seed") + 1 + ordinal + if (index < 5) 0 else 10000)
            }
        }
        if (final) {
            val main = (bySession.keys - known).single()
            val actual = bySession.getValue(main).sortedBy { it.ordinal }
            check(actual.size == 10000)
            actual.forEachIndexed { ordinal, row -> check(row.ordinal == ordinal && row.amount == state.getLong("seed") + 1 + ordinal) }
            if (state.has("mainInputRef")) check(main == state.getString("mainInputRef")) else state.put("mainInputRef", main)
        }
        check(snapshot.rows.size == if (final) 61000 else 51000)
        check(
            snapshot.rows
                .map { it.id }
                .toSet()
                .size == snapshot.rows.size,
        )
        check(
            snapshot.rows
                .map { it.source }
                .toSet()
                .size == snapshot.rows.size,
        )
        val frequencies = snapshot.rows.groupingBy { it.amount }.eachCount()
        check(frequencies.size == 11000)
        check(frequencies.values.count { it == if (final) 6 else 5 } == 10000)
        check(frequencies.values.count { it == 1 } == 1000)
        check(snapshot.relations == if (final) 150000 else 100000)
        val ranks = (0 until 5).associate { sessions.getJSONObject(it).getString("inputRef") to it }.toMutableMap()
        if (final) ranks[state.getString("mainInputRef")] = 5
        val pairCount = if (final) 15 else 10
        val seen = BooleanArray(10000 * pairCount)
        read { database, _ ->
            database
                .rawQuery(
                    "SELECT s.input_ref,s.record_ordinal,s.amount_minor,p.input_ref,p.record_ordinal,p.amount_minor FROM import_duplicate_candidate d JOIN import_source_record s ON s.ledger_id=d.ledger_id AND s.source_id=d.subject_source_id JOIN import_source_record p ON p.ledger_id=d.ledger_id AND p.source_id=d.possible_existing_source_id WHERE d.ledger_id=?",
                    arrayOf(state.getString("ledger")),
                ).use { cursor ->
                    var count = 0
                    while (cursor.moveToNext()) {
                        if (count++ % 1000 == 0) tick()
                        val subject = ranks.getValue(cursor.getString(0))
                        val previous = ranks.getValue(cursor.getString(3))
                        val ordinal = cursor.getInt(1)
                        check(subject > previous && ordinal in 0 until 10000)
                        check(ordinal == cursor.getInt(4) && cursor.getLong(2) == cursor.getLong(5))
                        val index = ordinal * pairCount + subject * (subject - 1) / 2 + previous
                        check(!seen[index]) { "duplicate endpoint pair" }
                        seen[index] = true
                    }
                    check(count == seen.size && seen.all { it }) { "relationship endpoints do not cover expected session pairs" }
                }
        }
    }

    fun zeroEconomics(snapshot: ScaleSnapshot) {
        listOf("ledger_transaction", "transaction_version", "posting", "import_confirmation", "import_candidate_decision_snapshot").forEach {
            check(snapshot.counts.getValue(it) == 0L) { "unexpected economic effect: $it" }
        }
    }

    fun relationIds(
        ledger: String,
        session: String,
        limit: Int? = null,
    ): List<Pair<String, String>> =
        read { database, _ ->
            val result = ArrayList<Pair<String, String>>()
            val query = "SELECT d.candidate_id,d.comparison_fingerprint FROM import_duplicate_candidate d JOIN import_source_record s ON s.ledger_id=d.ledger_id AND s.source_id=d.subject_source_id WHERE d.ledger_id=? AND s.input_ref=? ORDER BY s.record_ordinal,d.candidate_id"
            database.rawQuery(query + (limit?.let { " LIMIT $it" } ?: ""), arrayOf(ledger, session)).use { cursor ->
                while (cursor.moveToNext()) result += cursor.getString(0) to cursor.getString(1)
            }
            result
        }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    fun relationshipBaseline(
        ledger: String,
        sessions: List<String>,
    ): String =
        read { database, _ ->
            val placeholders = sessions.joinToString(",") { "?" }
            val scope = "SELECT d.candidate_id FROM import_duplicate_candidate d JOIN import_source_record s ON s.ledger_id=d.ledger_id AND s.source_id=d.subject_source_id WHERE d.ledger_id=? AND s.input_ref IN ($placeholders)"
            val tables = listOf("import_duplicate_candidate", "import_duplicate_status_history", "import_duplicate_review_snapshot", "import_duplicate_review_receipt")
            digestQueries(database, tables.map { "SELECT * FROM $it WHERE ledger_id=? AND candidate_id IN ($scope) ORDER BY 1,2,3" }, arrayOf(ledger, ledger, *sessions.toTypedArray()))
        }

    private fun digestQueries(
        database: SQLiteDatabase,
        queries: List<String>,
        args: Array<String>? = null,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        queries.forEachIndexed { index, query ->
            digest.update("table:$index\n".toByteArray())
            database.rawQuery(query, args).use { cursor ->
                var count = 0
                while (cursor.moveToNext()) {
                    if (count++ % 1000 == 0) tick()
                    for (column in 0 until cursor.columnCount) {
                        val value = if (cursor.isNull(column)) null else cursor.getString(column)
                        val bytes = value?.toByteArray()
                        digest.update("${bytes?.size ?: -1}:".toByteArray())
                        if (bytes != null) digest.update(bytes)
                    }
                    digest.update(10.toByte())
                }
            }
        }
        return digest.digest().hex()
    }
}
