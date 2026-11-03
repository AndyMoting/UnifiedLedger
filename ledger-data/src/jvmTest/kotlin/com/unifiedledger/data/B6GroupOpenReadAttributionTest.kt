package com.unifiedledger.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.ImportDuplicateReviewsForSessionResult
import com.unifiedledger.application.QueryImportDuplicateReviewsForSession
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.LedgerId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D-278 attribution, side A (test-only): the duplicate-group open read
 * (`SqlDelightImportReviewReadAdapter.loadImportDuplicateReviewsForSession`) on the registered
 * 20k duplicate-group library shape.
 *
 * D-278 §7 registered the attribution boundary: the click path is one session-level batch query
 * plus `enumerateImportDuplicateGroupItems`'s O(n) assembly, and the 3.6 s device reading was NOT
 * attributed. This test measures the read half on the host; the app-ui assembly half and the
 * device composition remain unmeasured here.
 *
 * SHAPE MEASURED: the product-shaped B6 fixture — schema v36, 2 candidates, 20,002 source records,
 * 20,000 duplicate relations on ONE pick session, and 20,000 duplicate-status-history rows (every
 * duplicate has a status, so the adapter's mapping never aborts). The session handle is DISCOVERED
 * (the `input_ref` with the most duplicate relations), never hard-coded: an artifact may carry
 * several pick sessions and only one is the group.
 *
 * FIXTURE SELECTION IS PART OF THE RESULT: an earlier attempt pointed this test at
 * `p707-a08-b6/b6-group-after.db`, which carries 30,000 duplicate relations but only 10,000 status
 * rows; the adapter aborts with `duplicate candidate has no status row` because that artifact was
 * hand-seeded past the product path. Any fixture used here must have one status row per duplicate
 * candidate.
 *
 * WHY A PREBUILT FIXTURE INSTEAD OF A BUILD: the same reason D-169's side-A test records — the
 * real spine's per-intake duplicate probe is O(n^2) over the history sources, so synthesizing 20k
 * relations through the product path costs hours. The measurement copies the file named by the
 * `UL_D278_FIXTURE_DB` environment variable to a temp path, opens it through the bundled JDBC
 * SQLite engine, and measures on it. No absolute path is embedded; when the variable is unset (or
 * names no readable file) the test prints a skip line and returns, so a machine without the
 * artifact stays green.
 *
 * HOST-SIDE READING: this is a host JVM reading, not a device reading. It attributes the read
 * against the device wall time; it does not reproduce it. Wall times are recorded and printed
 * (`D278ATTR ` lines) and never asserted — a time assertion would flake. The only asserted facts
 * are structural: the raw query returns one row per duplicate relation of the session, the
 * adapter's mapped list has the same length, and the use case resolves to `Reviews`.
 *
 * RECORDED OUTCOME (2026-11-03, host, `b6.db`, two independent runs of three rounds): raw session
 * query **295.5–499.6 ms**, adapter read (query + mapping) **303.2–803.6 ms**. The spread is host
 * load, not fixture variance — the second run overlapped other Gradle work — so the reading is a
 * RANGE, not a point.
 *
 * WHAT THAT DOES AND DOES NOT SETTLE: the read sits 4.5–12× below the device's 3.6 s. It therefore
 * cannot be dismissed as noise, and it cannot be confirmed as the whole either. With the emulator
 * slower than this host, the read plausibly accounts for anything between ~10% and ~55% of the
 * device time, which keeps it a live candidate for the dominant cost. `EXPLAIN QUERY PLAN` shows
 * every join and both correlated subqueries using primary-key indexes
 * (`import_duplicate_status_history`'s `(ledger_id, candidate_id, sequence)` covers `max(sequence)`
 * as a prefix seek), so there is no missing-index explanation to fix. D-171's side B (a
 * 61,011-item candidate render model, 10–50 ms) is a DIFFERENT shape and does not stand in for
 * B6's group assembly. Splitting the device 3.6 s needs stage marks inside the click path; this
 * test only bounds one stage from below on the host.
 */
class B6GroupOpenReadAttributionTest {
    @Test
    fun readTimeForThe20kDuplicateGroupSession() {
        val fixturePath = fixturePathOrNull()
        if (fixturePath == null) {
            println("D278ATTR: stage=A skipped=missing-fixture env=UL_D278_FIXTURE_DB")
            return
        }
        val copyStarted = System.nanoTime()
        val temp = Files.createTempFile("d278-attr-", ".db")
        try {
            try {
                Files.copy(fixturePath, temp, StandardCopyOption.REPLACE_EXISTING)
            } catch (failure: Exception) {
                // 存在但不可读（ACL、占用、瞬时 IO）同样按 skip 处理：这不是一个可测的夹具。
                println("D278ATTR: stage=A skipped=unreadable-fixture env=UL_D278_FIXTURE_DB ${failure::class.simpleName}")
                return
            }
            val copyMs = (System.nanoTime() - copyStarted) / 1_000_000
            val driver = JdbcSqliteDriver("jdbc:sqlite:${temp.absolutePathString()}")
            try {
                val database = LedgerDatabase(driver)
                val ledgerId = LedgerId(scalarText(driver, "SELECT ledger_id FROM import_candidate LIMIT 1"))
                val session = largestSession(driver, ledgerId)
                val candidates = scalarLong(driver, "SELECT count(*) FROM import_candidate")
                val duplicateRows = scalarLong(driver, "SELECT count(*) FROM import_duplicate_candidate")
                val adapter = SqlDelightImportReviewReadAdapter(database)

                // Warmup: discarded, so the measured rounds do not pay the first statement prepare.
                database.ledgerQueries
                    .importDuplicateReviewsForSession(ledgerId.value, session.inputRef)
                    .executeAsList()

                val rawTimes = mutableListOf<Long>()
                var rawRows = 0
                repeat(ROUNDS) {
                    val started = System.nanoTime()
                    rawRows =
                        database.ledgerQueries
                            .importDuplicateReviewsForSession(ledgerId.value, session.inputRef)
                            .executeAsList()
                            .size
                    rawTimes += System.nanoTime() - started
                }

                val adapterTimes = mutableListOf<Long>()
                var mappedRows = 0
                repeat(ROUNDS) {
                    val started = System.nanoTime()
                    val result =
                        requireNotNull(adapter.loadImportDuplicateReviewsForSession(ledgerId, session.inputRef)) {
                            "the group session must resolve to a review list, not Absent"
                        }
                    adapterTimes += System.nanoTime() - started
                    mappedRows = result.size
                }

                // Structural facts only: one raw row per duplicate relation of this session, and
                // the adapter's mapped list has the same length as the raw rows it maps.
                assertEquals(session.duplicateRelations, rawRows.toLong())
                assertEquals(rawRows, mappedRows)

                val verdict =
                    QueryImportDuplicateReviewsForSession(adapter).query(ledgerId, session.inputRef)
                assertTrue(
                    verdict is ImportDuplicateReviewsForSessionResult.Reviews,
                    "a present session with duplicates must resolve to Reviews, got $verdict",
                )

                println("D278ATTR: stage=A component=session-duplicate-review-read fixture=prebuilt-copy")
                println(
                    "D278ATTR: stage=A fixture-source=env:UL_D278_FIXTURE_DB ledger=${ledgerId.value} " +
                        "session=${session.inputRef} copy-ms=$copyMs",
                )
                println(
                    "D278ATTR: stage=A candidates=$candidates duplicate-rows=$duplicateRows " +
                        "session-duplicate-relations=${session.duplicateRelations} raw-rows=$rawRows",
                )
                println("D278ATTR: stage=A raw-query-ms=${rawTimes.map(::format)}")
                println("D278ATTR: stage=A adapter-read-ms=${adapterTimes.map(::format)}")
            } finally {
                driver.close()
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private data class SessionShape(
        val inputRef: String,
        val duplicateRelations: Long,
    )

    /** The pick session carrying the most duplicate relations — the group, discovered not assumed. */
    private fun largestSession(
        driver: JdbcSqliteDriver,
        ledgerId: LedgerId,
    ): SessionShape =
        driver
            .executeQuery(
                null,
                """
                SELECT subject.input_ref, count(*) AS relations
                FROM import_duplicate_candidate AS duplicate
                JOIN import_source_record AS subject
                  ON subject.ledger_id = duplicate.ledger_id
                 AND subject.source_id = duplicate.subject_source_id
                JOIN import_candidate AS candidate
                  ON candidate.ledger_id = duplicate.ledger_id
                 AND candidate.source_id = duplicate.subject_source_id
                WHERE duplicate.ledger_id = ?
                GROUP BY subject.input_ref
                ORDER BY relations DESC
                """.trimIndent(),
                { cursor ->
                    QueryResult.Value(
                        if (cursor.next().value) {
                            SessionShape(
                                inputRef = requireNotNull(cursor.getString(0)) { "input_ref is not null" },
                                duplicateRelations = requireNotNull(cursor.getLong(1)) { "relations is not null" },
                            )
                        } else {
                            error("the fixture has no duplicate relations at all")
                        },
                    )
                },
                1,
            ) { bindString(0, ledgerId.value) }
            .value

    /**
     * The fixture path from `UL_D278_FIXTURE_DB`, or null when it is unset or names no readable
     * file. No absolute path is embedded in this tracked file.
     */
    private fun fixturePathOrNull(): Path? {
        val configured = System.getenv(FIXTURE_ENV) ?: return null
        if (configured.isBlank()) return null
        val path = Path.of(configured)
        return if (Files.isRegularFile(path)) path else null
    }

    private fun scalarText(
        driver: JdbcSqliteDriver,
        sql: String,
    ): String =
        driver
            .executeQuery(
                null,
                sql,
                { cursor -> QueryResult.Value(if (cursor.next().value) requireNotNull(cursor.getString(0)) else "") },
                0,
            ).value

    private fun scalarLong(
        driver: JdbcSqliteDriver,
        sql: String,
    ): Long =
        driver
            .executeQuery(
                null,
                sql,
                { cursor -> QueryResult.Value(if (cursor.next().value) requireNotNull(cursor.getLong(0)) else 0L) },
                0,
            ).value

    private fun format(nanos: Long): String = String.format(Locale.ROOT, "%.1f", nanos / 1_000_000.0)

    private companion object {
        const val FIXTURE_ENV = "UL_D278_FIXTURE_DB"
        const val ROUNDS = 3
    }
}
