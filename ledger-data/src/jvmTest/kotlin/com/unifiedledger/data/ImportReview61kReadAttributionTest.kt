package com.unifiedledger.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.ImportDuplicateStatus
import com.unifiedledger.application.ImportReviewRow
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.LedgerId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * D-169 attribution, side A (test-only): the paged DB read plus result construction
 * (`SqlDelightImportReviewReadAdapter.loadImportReviewRows`) on the registered 61k-candidate
 * library shape. D-169 section 5 registered an operability defect (~11 s tap-to-visible on a
 * 61k-candidate device library, above the OOM fix's frozen <=3 s threshold) and located the cost
 * in the UI layer by bounded read-only code inspection, but did NOT attribute the 11 s between
 * (A) this read and (B) the app-ui render-model build. This test measures (A) alone; the companion
 * app-ui test measures (B).
 *
 * WHY A PREBUILT FIXTURE INSTEAD OF A BUILD: the first version of this test reproduced the
 * 61k-candidate library with raw bulk inserts inside one transaction (the established
 * `P705Harness.insertOrdinaryBulk` discipline, chosen because the real spine's per-intake duplicate
 * probe has no covering index and would cost O(n^2) scans). That build did not finish within
 * 40+ minutes on this host — the fixture, not the measurement, was the cost. The measurement now
 * reuses the already-materialized 61k-candidate library instead: it copies the file named by the
 * `UL_D169_FIXTURE_DB` environment variable to a temp path, opens it read-write through the bundled
 * JDBC SQLite engine, and measures the read on it. No absolute path is embedded; when the variable
 * is unset (or names no readable file) the test prints a skip line and returns, so a machine without
 * the artifact stays green.
 *
 * FIXTURE SHAPE (verified against the artifact before asserting anything): 61,000 candidates for one
 * ledger, 158,000 duplicate relations, `user_version=31`. The paged list query returns one row per
 * (candidate, duplicate relation) pair — 61,000 + 158,000 = 219,000 rows on the D-166 synthetic
 * profile; on this artifact it is 168,000 (every candidate has its base row; only the duplicate-
 * bearing candidates add relation rows, and the artifact's relation layout differs from the D-166
 * 9,800x6 + 200x11 grouping, so the exact query-row count is read from the DB, never assumed).
 * Likewise the D-166 duplicate-status mix (10,000 null / 51,000 DEFERRED) is NOT asserted: the
 * artifact's folded mix is read and printed (this copy carries 10,000 null / 50,600 DEFERRED /
 * 400 CONFIRMED_DUPLICATE). The only asserted fact is the one the read must satisfy structurally:
 * the folded list has exactly one row per candidate in the DB.
 *
 * HOST-SIDE READING. This is a host JVM reading through the bundled JDBC SQLite engine, not a
 * device reading; it attributes (A) vs (B), it does not reproduce the device wall time. Wall times
 * are recorded only, never asserted (a time assertion would flake); the printed `D169ATTR ` lines
 * are the evidence.
 *
 * ATTRIBUTION OUTCOME (recorded, not proven): on this host side A is ~2.0-2.3 s/round and side B
 * (the app-ui render-model build) is ~10-50 ms/round — together only ~2.4 s of the registered
 * ~11 s. The residual ~8.6 s is therefore NOT explained by these host-side components and is most
 * plausibly device-side UI composition/recomposition, which these tests do not measure. This is an
 * attribution of the measured host-side work, not a proven device root cause.
 */
class ImportReview61kReadAttributionTest {
    @Test
    fun readTimeForThe61kCandidateLibrary() {
        val fixturePath = fixturePathOrNull()
        if (fixturePath == null) {
            println("D169ATTR: side=A skipped=missing-fixture env=UL_D169_FIXTURE_DB")
            return
        }
        val copyStarted = System.nanoTime()
        val temp = Files.createTempFile("d169-attr-", ".db")
        try {
            Files.copy(fixturePath, temp, StandardCopyOption.REPLACE_EXISTING)
            val copyMs = (System.nanoTime() - copyStarted) / 1_000_000
            val driver = JdbcSqliteDriver("jdbc:sqlite:${temp.absolutePathString()}")
            try {
                val database = LedgerDatabase(driver)
                val ledgerId = LedgerId(scalarText(driver, "SELECT ledger_id FROM import_candidate LIMIT 1"))
                val candidates = scalarLong(driver, "SELECT count(*) FROM import_candidate")
                val duplicateRows = scalarLong(driver, "SELECT count(*) FROM import_duplicate_candidate")
                val adapter = SqlDelightImportReviewReadAdapter(database)
                // Warmup: discarded, so the measured rounds do not pay the first-call statement
                // prepare and page-cache warm-up.
                adapter.loadImportReviewRows(ledgerId)
                val readTimes = mutableListOf<Long>()
                var folded: List<ImportReviewRow> = emptyList()
                repeat(ROUNDS) {
                    val started = System.nanoTime()
                    folded = adapter.loadImportReviewRows(ledgerId)
                    readTimes += System.nanoTime() - started
                }
                // The folded result is exactly one row per candidate; the duplicate-status mix is
                // read from the artifact, not assumed (it is not the D-166 profile on this copy).
                assertEquals(candidates, folded.size.toLong())
                val mix = folded.groupingBy { it.duplicateStatus }.eachCount()
                println("D169ATTR: side=A component=paged-read-and-fold fixture=prebuilt-copy")
                println("D169ATTR: side=A fixture-source=env:UL_D169_FIXTURE_DB ledger=${ledgerId.value} copy-ms=$copyMs")
                println("D169ATTR: side=A candidates=$candidates duplicate-rows=$duplicateRows folded-rows=${folded.size}")
                println(
                    "D169ATTR: side=A duplicate-status-mix " +
                        ImportDuplicateStatus.entries.joinToString(" ") { "${it.name}=${mix[it] ?: 0}" } +
                        " null=${mix[null] ?: 0}",
                )
                readTimes.forEachIndexed { index, nanos ->
                    println("D169ATTR: side=A round=${index + 1} read-ms=${format(nanos)}")
                }
                println("D169ATTR: side=A read-min-ms=${format(readTimes.min())} read-max-ms=${format(readTimes.max())}")
            } finally {
                driver.close()
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /**
     * The fixture path from `UL_D169_FIXTURE_DB`, or null when it is unset or names no readable
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

    private fun format(nanos: Long): String = String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0)

    private companion object {
        const val ROUNDS = 3
        const val FIXTURE_ENV = "UL_D169_FIXTURE_DB"
    }
}
