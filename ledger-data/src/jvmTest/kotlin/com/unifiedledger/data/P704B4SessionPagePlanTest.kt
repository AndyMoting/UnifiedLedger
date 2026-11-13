package com.unifiedledger.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.data.db.LedgerDatabase
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P7-04 B1/B4 windowed read (design `docs/specs/2026-11-13-p7-04-b1-b4-windowed-read-design.md` D-7,
 * acceptance V-9; D-8 second slice extends the pin set, spec §14): the query-plan pin for the
 * candidate-row queries and the member-review page query.
 *
 * WHY THIS TEST IS NOT A HAND-COPIED SQL PIN (D-283 residual R2-14). The existing equivalence tests
 * that carry a plan pin transcribe a simplified statement by hand, so the plan they print is the plan
 * of a statement nobody ships until someone notices the drift. This test instead reads the tracked
 * `Ledger.sq`, extracts the named query's own text, substitutes the named parameters with literals (a
 * plan is a function of the statement text and the schema, never of the bound values) and runs
 * `EXPLAIN QUERY PLAN` through the real JDBC SQLite engine. If the production query changes, the
 * printed plan changes with it.
 *
 * WHAT IS ASSERTED: only that the plan is non-empty and contains at least one access path — the plan
 * was actually produced for the statement, rather than the query silently failing open. WHAT IS
 * DELIBERATELY NOT ASSERTED: that the plan avoids a `SCAN`. D-7 makes the per-output-row cost a
 * measurement followed by a RULING, and a `SCAN` on the `import_evidence` probe is exactly the input
 * that ruling may use; asserting "no scan" here would convert a design question into a test failure
 * and pressure the next change into tuning a query this batch must not tune.
 *
 * The printed lines carry the `P704B4-PLAN ` prefix, which `tools/ci/scale-readings-report.py`
 * surfaces into the job summary (design §4 S3), so the plan becomes citable evidence after the job
 * ends instead of scrolling out of the log.
 */
class P704B4SessionPagePlanTest {
    @Test
    fun theLedgerPageQueryPlanHasAnAccessPath() {
        val plan = planFor(LEDGER_PAGE_QUERY)
        plan.forEach { detail -> println("P704B4-PLAN query=$LEDGER_PAGE_QUERY $detail") }
        assertAccessPath(LEDGER_PAGE_QUERY, plan)
    }

    @Test
    fun theSessionPageQueryPlanHasAnAccessPath() {
        val plan = planFor(SESSION_PAGE_QUERY)
        plan.forEach { detail -> println("P704B4-PLAN query=$SESSION_PAGE_QUERY $detail") }
        assertAccessPath(SESSION_PAGE_QUERY, plan)
    }

    @Test
    fun theCandidatesQueryPlanHasAnAccessPath() {
        // D-8 second slice (design spec §14; D-321): the candidate-id-set read rides the SAME
        // projection the ledger page query carries, so its plan pin exists for the same reason —
        // the plan of the statement the generated code actually ships, read off the tracked
        // Ledger.sq. The IN-collection bind expands to a literal tuple (a plan is a function of the
        // statement text, and the expansion is the statement's real parameter surface).
        val plan = planFor(CANDIDATES_QUERY)
        plan.forEach { detail -> println("P704B4-PLAN query=$CANDIDATES_QUERY $detail") }
        assertAccessPath(CANDIDATES_QUERY, plan)
    }

    @Test
    fun theMemberReviewPageQueryPlanHasAnAccessPath() {
        // Spec §11 item 4 (D-8 closure leg): the member-major duplicate-review page is the second
        // read whose cost the D-7 measurement leg covers, so it gets the same EXPLAIN pin. The
        // statement is read off the tracked Ledger.sq and bound with the same literal discipline.
        val plan = planFor(MEMBER_PAGE_QUERY)
        plan.forEach { detail -> println("P704B4-PLAN query=$MEMBER_PAGE_QUERY $detail") }
        assertAccessPath(MEMBER_PAGE_QUERY, plan)
    }

    @Test
    fun theSessionPageQueryIsTheLedgerPageQueryPlusTheSessionPredicate() {
        val ledgerLines = queryStatement(LEDGER_PAGE_QUERY).lines()
        val sessionLines = queryStatement(SESSION_PAGE_QUERY).lines()

        assertEquals("SELECT", ledgerLines.first(), "the extracted statement must start at the projection")
        assertEquals(ledgerLines.first(), sessionLines.first())
        assertEquals(columnsOf(ledgerLines), columnsOf(sessionLines), "the column list must be byte-identical to the ledger page query")
        assertEquals(joinsOf(ledgerLines), joinsOf(sessionLines), "the FROM / JOIN block must be byte-identical to the ledger page query")
        assertEquals(orderByOf(ledgerLines), orderByOf(sessionLines), "the ORDER BY must be byte-identical to the ledger page query")
        // Exactly one predicate is added, and the keyset shape around it is untouched: the session arm
        // inside the candidate-boundary IN subquery plus the outer session filter that repeats it.
        assertEquals(
            listOf(
                "  AND source.input_ref = :session_input_ref",
                "      AND source_id IN (",
                "        SELECT source_id FROM import_source_record",
                "        WHERE ledger_id = :page_ledger_id AND input_ref = :session_input_ref",
                "      )",
            ),
            sessionLines.filterNot { line -> line in ledgerLines },
            "the session page query may add the session predicate and nothing else",
        )
    }

    /**
     * The named query's own statement text from the tracked `Ledger.sq`: the query-name line, the
     * leading `--` documentation and the terminating `;` are stripped, everything else is the
     * statement the generated code ships.
     */
    private fun queryStatement(name: String): String {
        val source = Files.readString(migrationRepositoryFile(SQL_FILE))
        val lines = source.lines()
        val start = lines.indexOfFirst { line -> line.trimEnd() == "$name:" }
        require(start >= 0) { "named query $name not found in $SQL_FILE" }
        val body = lines.drop(start + 1).takeWhile { line -> !QUERY_NAME.matches(line) }
        require(body.isNotEmpty()) { "named query $name has no body in $SQL_FILE" }
        return body
            .filterNot { line -> line.trimStart().startsWith("--") }
            .joinToString("\n")
            .trim()
            .removeSuffix(";")
    }

    /**
     * The named parameters bound to literals. They are the union of the pinned statements' full
     * parameter surfaces, so an added parameter fails loud here instead of producing a plan of a
     * statement that no longer matches production.
     */
    private fun bindLiterals(statement: String): String =
        statement.replace(PARAMETER) { match ->
            BINDS[match.groupValues[1]]
                ?: error("the plan pin does not know the named parameter ${match.value}; add it to BINDS")
        }

    private fun planFor(name: String): List<String> {
        val statement = bindLiterals(queryStatement(name))
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            return driver
                .executeQuery(
                    0,
                    "EXPLAIN QUERY PLAN $statement",
                    mapper = { cursor ->
                        val details = mutableListOf<String>()
                        while (cursor.next().value) {
                            details += cursor.getString(3) ?: ""
                        }
                        QueryResult.Value(details)
                    },
                    parameters = 0,
                ).value
        } finally {
            driver.close()
        }
    }

    private fun assertAccessPath(
        name: String,
        plan: List<String>,
    ) {
        assertTrue(plan.isNotEmpty(), "the $name plan must not be empty")
        assertTrue(
            plan.any { detail -> detail.contains("SCAN") || detail.contains("SEARCH") },
            "the $name plan must contain at least one access path (SCAN/SEARCH): $plan",
        )
    }

    private fun columnsOf(lines: List<String>): List<String> = lines.drop(1).takeWhile { line -> line != FROM_LINE }

    private fun joinsOf(lines: List<String>): List<String> = lines.dropWhile { line -> line != FROM_LINE }.takeWhile { line -> line != OUTER_WHERE_LINE }

    private fun orderByOf(lines: List<String>): String = lines.last()

    private companion object {
        const val SQL_FILE = "ledger-data/src/commonMain/sqldelight/com/unifiedledger/data/db/Ledger.sq"
        const val LEDGER_PAGE_QUERY = "importReviewRowsForLedgerPage"
        const val SESSION_PAGE_QUERY = "importReviewRowsForSessionPage"
        const val CANDIDATES_QUERY = "importReviewRowsForCandidates"
        const val MEMBER_PAGE_QUERY = "importDuplicateReviewsForSessionMemberPage"
        const val FROM_LINE = "FROM import_candidate AS candidate"
        const val OUTER_WHERE_LINE = "WHERE candidate.ledger_id = :ledger_id"
        val QUERY_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*:\\s*$")
        val PARAMETER = Regex(":([a-z_]+)")
        val BINDS =
            mapOf(
                "ledger_id" to "'ledger-p704-b4-plan'",
                "page_ledger_id" to "'ledger-p704-b4-plan'",
                "session_input_ref" to "'session-p704-b4-plan'",
                "after_candidate_id" to "''",
                "page_size" to "200",
                // D-8 second slice: the candidate-id-set read's IN-collection bind. The literal
                // tuple is the statement's real parameter surface (the adapter passes the caller's
                // id collection; a plan is a function of the statement text, and the expanded tuple
                // is what reaches SQLite).
                "candidate_ids" to "('p704-b4-plan-1','p704-b4-plan-2')",
            )
    }
}
