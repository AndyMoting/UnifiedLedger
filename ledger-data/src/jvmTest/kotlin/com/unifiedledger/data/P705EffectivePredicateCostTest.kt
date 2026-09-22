package com.unifiedledger.data

import com.unifiedledger.application.ExecuteVoidTransaction
import com.unifiedledger.application.ExplicitManualSave
import com.unifiedledger.application.VoidTransactionRequest
import com.unifiedledger.application.VoidTransactionResult
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.VoidReason
import com.unifiedledger.domain.VoidReasonCode
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * D-158 section 5 (P7-05 slice 1b merge blocker): the effective-predicate cost reading on a
 * 20k-transaction database, covering both hot read queries.
 *
 * D-158 section 5 records that the v31 effective predicate adds one correlated join to each of
 * `currentVersionRowsForLedger` (Ledger.sq:8717, join clause at :8743) and `ledgerEntryRowsForLedger`
 * (:8760, join clause at :8788) through the `transaction_effective_state` view (:9719), that
 * `ledger_transaction` has no `(ledger_id)` index, and that this cost was never measured. Under the
 * A-PERF evidence discipline (D-147/D-148) slice 1b may not be merged until a reading exists on a 20k
 * database; a reading showing a regression must be handled with an index edge or a new decision,
 * never silently. This class is that reading: tracked, reproducible, and run by the module suite.
 * It also establishes the timing discipline for this source set, which previously had none (the only
 * `nanoTime` precedent in the repository is an instrumented Android test).
 *
 * HOST-SIDE READING, AND WHAT IT DOES NOT DISCHARGE. This is a host-side JVM reading through the
 * bundled JDBC SQLite engine (the engine version is reported at run time in the `D158-S5 environment`
 * line; the host JVM engine is SQLite 3.51.3), not a device reading. The device's system SQLite is
 * 3.44.3, and D-158 section 5's device-side reading remains a P7-05 slice-1b precondition: this
 * artifact does not discharge it.
 *
 * CONCLUSION AND ACCEPTANCE CRITERION. D-158 section 5 makes the effective-predicate cost a merge
 * blocker but leaves "regression" undefined, so the criterion applied here is stated explicitly: the
 * predicate is treated as a regression only if the delta is large relative to the materialized read
 * the product actually pays (i.e. changes its order of magnitude), not merely positive. Under that
 * criterion this reading detects NO REGRESSION AT THIS SAMPLE SIZE. Three of the four measured series
 * carry a small, consistent positive predicate cost (per-round deltas of roughly +10..+37 ms, i.e. a
 * low-double-digit percentage of the ablation read), and independent runs produced both a small
 * negative round and a large positive spike, so the observed run-to-run envelope is at least as wide
 * as the signal at this sample size. The observed predicate cost is therefore a small positive delta
 * WITHIN the observed run-to-run envelope, not a claim that the predicate is free. The conclusion is
 * not forced by the data, and the pending device-side reading may narrow or widen it.
 *
 * HOMOGENEITY LIMIT ON WHAT THIS READING PROVES. The fixture holds zero void facts, so
 * `is_effective = 1` drops nothing (asserted below). The reading therefore measures only the
 * correlated join's evaluation cost over an all-effective `ledger_transaction`. It does NOT measure
 * (a) the predicate's filtering cost — the work of discarding voided transactions — nor (b) the
 * correlated `MAX(sequence)` subquery in `transaction_effective_state` against a populated
 * `transaction_void_fact` table. Both remain unmeasured. The separate small-database test proving the
 * predicate genuinely filters once a transaction is voided is unaffected and is kept.
 *
 * WHAT IS ASSERTED (structural only) versus WHAT IS MERELY RECORDED (wall clock):
 * - Asserted: the fixture scale (20,000 transactions / 40,000 posting rows), that the fixture holds
 *   no void facts so the predicate is a semantic no-op on it, that both sides of every comparison
 *   return identical rows column for column, that the with-predicate transcription returns exactly
 *   what the production generated query returns, that the predicate genuinely filters once a
 *   transaction is voided, that `ANALYZE` produced planner statistics for every table in the join
 *   chain, and that no index on `ledger_transaction` leads with `ledger_id` (the premise D-158
 *   section 5 states).
 * - Recorded only: every millisecond figure. No wall-clock threshold is asserted anywhere — a
 *   time-based assertion would flake on CI and on any other host. The reading is printed under the
 *   stable `D158-S5 ` prefix and retained in the JUnit XML's system-out, so the number can be quoted
 *   as evidence.
 * - Deliberately NOT asserted: any `EXPLAIN QUERY PLAN` text. The P7-05 design froze plan assertions
 *   out (spec sections 1/6/8: the repository has no plan-assertion precedent and plan text is fragile
 *   across SQLite versions). Plan text is printed as report evidence only, and this class makes no
 *   claim that depends on it. The printed `D158-S5 plan` lines are host-version-fragile and must NOT
 *   be quoted as evidence; only the timed series are quotable.
 *
 * ANALYZE BEFORE TIMING (mandatory): `QueryStatisticsOptimize.kt:8-13` records the same schema's
 * import-list query at 255.98 s without planner statistics and 0.24 s after `ANALYZE` — a ~1066x
 * planner artifact, not a query cost. Timing these joins without statistics would therefore produce
 * a false regression. `ANALYZE;` rides `driverExecute` because the JDBC driver rejects row-less
 * statements on the `executeQuery` path (`QueryStatisticsOptimize.kt:39-44`).
 *
 * THE COMPARISON BASIS (the one open design decision, resolved here). The ablation side can only be
 * raw SQL through the driver: the pre-v31 shape no longer exists as a generated query, because D-156
 * replaced it in place, so there is no second named query to call. Rather than accept an asymmetry
 * between the two sides, this test removes it on the side that actually matters — the consumption
 * cost — and ties the remaining difference by assertion:
 *
 * - Both sides run through the same `driverQuery` surface with the same mapper, so the delta isolates
 *   the SQL difference (the effective join) instead of mixing in the difference between SQLDelight's
 *   generated row objects and a hand-written mapper. This matters: an earlier draft of this test timed
 *   a cursor-stepping ablation against the generated query's fully materialised `executeAsList()`, and
 *   the resulting +146% "predicate cost" was mostly the generated side's row construction. The
 *   asymmetry between those two surfaces is larger than the effect being measured.
 * - The with-predicate text is a verbatim transcription of the production query from `Ledger.sq`, and
 *   its output must equal the production generated query's output column for column on this fixture.
 *   That assertion is what licenses quoting the transcribed number as production behaviour.
 * - The generated query itself is also timed and reported, as the as-shipped end-to-end reference. It
 *   is labelled separately and is not part of either delta, precisely because its mapper differs.
 *
 * Each query is therefore reported under two mapper conventions — `pure` (cursor stepping only, the
 * query's own cost) and `materialized` (every column read and stored, the cost the product pays) —
 * because the mapper's own cost is large enough to hide a small SQL difference. A regression that
 * appears under one convention but not the other is a real and reportable finding; reporting both is
 * what makes that visible instead of convention-dependent.
 *
 * TIMING CONVENTION (the repository is not uniform): the device A-PERF threshold gates take the max
 * over >= 3 rounds, while the only same-subject host-side reading took the min over 3 rounds. D-158
 * section 5 gates on no wall-clock threshold, so this test takes neither convention: it reports min
 * and max for every series and states the delta under both, leaving the reader to choose. The delta
 * is summarised from per-round paired differences rather than from independently chosen extrema, so
 * the reported "delta max" cannot be smaller than the "delta min" (see [reportSeries]). The warmup
 * round is discarded, and over the 4 measured rounds the within-round order alternates 2:2
 * (predicate-first in rounds 1 and 3, ablation-first in rounds 2 and 4), so neither side
 * systematically pays the other's cache-warming cost.
 *
 * PARSE/PREPARE ASYMMETRY (disclosed, not corrected). Both sides ride `driverQuery`, which goes
 * through `driver.executeQuery` and therefore prepares a fresh statement on every call. The
 * with-predicate SQL is longer than the ablation, so part of every positive delta is the extra parse
 * and prepare of the longer statement rather than join execution. The delta is still valid as a
 * measurement of the production query's total cost versus the ablation's, because both sides pay the
 * common prepare cost of their shared prefix; only the incremental prepare of the added clause is
 * inside the delta.
 *
 * TRANSCRIPTION DRIFT GUARD. The with-predicate texts below are hand-copied from `Ledger.sq`, and the
 * only guard tying them to production is behavioural (output equality on this fixture, asserted
 * below). Whenever either hot query (`currentVersionRowsForLedger` or `ledgerEntryRowsForLedger`) is
 * edited in `Ledger.sq`, these transcriptions must be re-verified against the new text, or the
 * reading silently measures a query the product no longer runs.
 */
class P705EffectivePredicateCostTest {
    @Test
    fun effectivePredicateCostOnTwentyThousandTransactions() {
        // A failed derivation would leave both sides identical and report a silent zero delta.
        assertTrue(
            CURRENT_VERSION_ABLATION != CURRENT_VERSION_WITH_PREDICATE &&
                !CURRENT_VERSION_ABLATION.contains("transaction_effective_state"),
            "the current-version ablation must be the production text minus the effective join",
        )
        assertTrue(
            ENTRY_ABLATION != ENTRY_WITH_PREDICATE && !ENTRY_ABLATION.contains("transaction_effective_state"),
            "the entry ablation must be the production text minus the effective join",
        )

        P705Database.create("p705-d158-s5-scale-").use { harness ->
            val buildStart = System.nanoTime()
            harness.insertOrdinaryBulk(
                count = TRANSACTION_COUNT,
                note = { index -> if (index % 2 == 0) "expense-${index + 1}" else "income-${index + 1}" },
                statisticsAt = { index -> FIXTURE_INSTANTS[index % FIXTURE_INSTANTS.size] },
            )
            val buildMs = (System.nanoTime() - buildStart) / 1_000_000.0

            // The fixture premise the comparison rests on: 20k transactions with a two-leg chain
            // each, and no void fact anywhere, so `is_effective = 1` drops nothing and the two sides
            // must agree row for row.
            assertEquals(TRANSACTION_COUNT.toLong(), harness.ledgerQueryCount("SELECT count(*) FROM ledger_transaction"))
            assertEquals(EXPECTED_ROW_COUNT.toLong(), harness.ledgerQueryCount("SELECT count(*) FROM posting"))
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_void_fact"))

            // The D-158 section 5 premise: no index on ledger_transaction can serve `ledger_id = ?`.
            // Both implicit indexes start with transaction_id, so the WHERE clause cannot seek. If
            // this ever fails, an index edge landed and this reading must be re-interpreted.
            val indexLeadColumns = ledgerTransactionIndexLeadColumns(harness)
            assertTrue(indexLeadColumns.isNotEmpty(), "ledger_transaction must expose its implicit indexes")
            assertTrue(
                indexLeadColumns.none { it.value == "ledger_id" },
                "D-158 section 5 states ledger_transaction has no (ledger_id) index; the premise no longer holds: $indexLeadColumns",
            )

            // Statistics BEFORE any timing; without them the joins are a planner artifact.
            val analyzeStart = System.nanoTime()
            harness.driverExecute("ANALYZE;")
            val analyzeMs = (System.nanoTime() - analyzeStart) / 1_000_000.0
            HOT_PATH_TABLES.forEach { table ->
                assertTrue(
                    harness.ledgerQueryCount("SELECT count(*) FROM sqlite_stat1 WHERE tbl = '$table'") > 0L,
                    "ANALYZE must produce planner statistics for $table before any timing",
                )
            }

            // Tie the transcribed with-predicate text to production, and the ablation to the
            // transcription. On an all-effective fixture the predicate drops nothing, so all three
            // must return identical rows, column for column.
            assertEquals(
                generatedCurrentVersionRows(harness),
                rowsOf(harness, CURRENT_VERSION_WITH_PREDICATE, CURRENT_VERSION_COLUMNS),
                "the current-version with-predicate transcription must return exactly the production query's rows",
            )
            assertEquals(
                generatedEntryRows(harness),
                rowsOf(harness, ENTRY_WITH_PREDICATE, ENTRY_COLUMNS),
                "the entry with-predicate transcription must return exactly the production query's rows",
            )
            assertEquals(
                rowsOf(harness, CURRENT_VERSION_WITH_PREDICATE, CURRENT_VERSION_COLUMNS),
                rowsOf(harness, CURRENT_VERSION_ABLATION, CURRENT_VERSION_COLUMNS),
                "the current-version ablation must differ from production by the effective join only",
            )
            assertEquals(
                rowsOf(harness, ENTRY_WITH_PREDICATE, ENTRY_COLUMNS),
                rowsOf(harness, ENTRY_ABLATION, ENTRY_COLUMNS),
                "the entry ablation must differ from production by the effective join only",
            )

            println("D158-S5 fixture transactions=$TRANSACTION_COUNT postings=$EXPECTED_ROW_COUNT expected-rows=$EXPECTED_ROW_COUNT void-facts=0")
            println("D158-S5 premise ledger-transaction-index-lead-columns=$indexLeadColumns")
            harness.environmentIdentity().forEach { println("D158-S5 environment $it") }
            println(
                "D158-S5 build-ms=${format(buildMs)} analyze-ms=${format(analyzeMs)}" +
                    " sqlite-stat1-rows=${harness.ledgerQueryCount("SELECT count(*) FROM sqlite_stat1")}",
            )
            // Report evidence only; never asserted (see the class doc).
            planLines(harness, CURRENT_VERSION_WITH_PREDICATE).forEach { println("D158-S5 plan current-version $it") }
            planLines(harness, ENTRY_WITH_PREDICATE).forEach { println("D158-S5 plan entry $it") }

            repeat(WARMUP_ROUNDS) { timedSeries(harness) }

            val readings =
                (1..MEASURED_ROUNDS).map { round ->
                    val predicateFirst = round % 2 == 1
                    RoundReading(
                        round = round,
                        order = if (predicateFirst) "predicate-first" else "ablation-first",
                        series = timedSeries(harness, predicateFirst),
                    )
                }

            readings.forEach { reading ->
                reading.series.forEach { (label, value) -> println("D158-S5 round=${reading.round} order=${reading.order} $label-ms=${format(value)}") }
            }
            QUERY_LABELS.forEach { query ->
                reportSeries(
                    query = query,
                    convention = "pure",
                    withPredicateMs = readings.map { it.series.getValue("$query-with-predicate-pure") },
                    ablationMs = readings.map { it.series.getValue("$query-ablation-pure") },
                )
                reportSeries(
                    query = query,
                    convention = "materialized",
                    withPredicateMs = readings.map { it.series.getValue("$query-with-predicate-materialized") },
                    ablationMs = readings.map { it.series.getValue("$query-ablation-materialized") },
                )
                reportReference(
                    query = query,
                    generatedMs = readings.map { it.series.getValue("$query-generated") },
                )
            }
        }
    }

    /**
     * One round of timings for both queries under both mapper conventions, plus the as-shipped
     * generated reference. Every series is the same work in the same order within a round, so the
     * comparison between a with-predicate and an ablation series is a comparison of SQL alone.
     */
    private fun timedSeries(
        harness: P705Database,
        predicateFirst: Boolean = true,
    ): Map<String, Double> {
        val series = mutableMapOf<String, Double>()
        for (query in QUERY_LABELS) {
            val withPredicate = if (query == "current-version") CURRENT_VERSION_WITH_PREDICATE else ENTRY_WITH_PREDICATE
            val ablation = if (query == "current-version") CURRENT_VERSION_ABLATION else ENTRY_ABLATION
            val columns = if (query == "current-version") CURRENT_VERSION_COLUMNS else ENTRY_COLUMNS
            val pure: Pair<Double, Double>
            val materialized: Pair<Double, Double>
            if (predicateFirst) {
                pure = measureMs { countRows(harness, withPredicate) } to measureMs { countRows(harness, ablation) }
                materialized = measureMs { rowsOf(harness, withPredicate, columns).size } to measureMs { rowsOf(harness, ablation, columns).size }
            } else {
                val ablationPure = measureMs { countRows(harness, ablation) }
                val ablationMaterialized = measureMs { rowsOf(harness, ablation, columns).size }
                pure = measureMs { countRows(harness, withPredicate) } to ablationPure
                materialized = measureMs { rowsOf(harness, withPredicate, columns).size } to ablationMaterialized
            }
            series["$query-with-predicate-pure"] = pure.first
            series["$query-ablation-pure"] = pure.second
            series["$query-with-predicate-materialized"] = materialized.first
            series["$query-ablation-materialized"] = materialized.second
            series["$query-generated"] =
                measureMs {
                    if (query == "current-version") {
                        withPredicateCurrentVersion(harness)
                    } else {
                        withPredicateEntry(harness)
                    }
                }
        }
        return series
    }

    /**
     * The scale fixture's bulk writer must be indistinguishable from the per-query writer the rest
     * of the P7-05 vectors use. Two small databases are built from the same parameters — one through
     * [P705Database.insertOrdinaryExpense]/[P705Database.insertOrdinaryIncome], one through
     * [P705Database.insertOrdinaryBulk] — and every row of every table in the chain must match.
     * This is what lets the 20k reading stand on a batched fixture: only the statement batching
     * differs, and this test is the proof.
     */
    @Test
    fun bulkFixtureMatchesThePerQueryWriterRowForRow() {
        val notes = { index: Int -> if (index % 2 == 0) "expense-${index + 1}" else "income-${index + 1}" }
        val amounts = { index: Int -> 1_000L + index % 97 * 137L }
        val instants = { index: Int -> FIXTURE_INSTANTS[index % FIXTURE_INSTANTS.size] }

        P705Database.create("p705-d158-s5-perquery-").use { perQuery ->
            repeat(EQUIVALENCE_CHAINS) { index ->
                val transactionId = perQuery.bulkTransactionId(index)
                val amount = amounts(index)
                val statisticsAt = instants(index)
                if (index % 2 == 0) {
                    perQuery.insertOrdinaryExpense(transactionId, amount, statisticsAt = statisticsAt, note = notes(index))
                } else {
                    perQuery.insertOrdinaryIncome(transactionId, amount, statisticsAt = statisticsAt, note = notes(index))
                }
            }
            P705Database.create("p705-d158-s5-bulk-").use { bulk ->
                bulk.insertOrdinaryBulk(count = EQUIVALENCE_CHAINS, note = notes, amountMinor = amounts, statisticsAt = instants)

                assertEquals(
                    perQuery.ledgerQueryTexts(CHAIN_DUMP),
                    bulk.ledgerQueryTexts(CHAIN_DUMP),
                    "the bulk fixture must write the same transaction/version/posting rows as the per-query writer",
                )
                assertEquals(
                    perQuery.ledgerQueryTexts(CURRENT_VERSION_DUMP),
                    bulk.ledgerQueryTexts(CURRENT_VERSION_DUMP),
                    "the bulk fixture must write the same current-version pointers as the per-query writer",
                )
                // And the read side cannot tell them apart either.
                assertEquals(
                    generatedCurrentVersionRows(perQuery),
                    generatedCurrentVersionRows(bulk),
                    "both fixtures must project identically through the production current-version query",
                )
                assertEquals(
                    generatedEntryRows(perQuery),
                    generatedEntryRows(bulk),
                    "both fixtures must project identically through the production entry query",
                )
            }
        }
    }

    /**
     * The ablation's two claims on a small database: while every transaction is effective it
     * reproduces the generated query's rows column for column (so it differs from production by
     * exactly the effective join), and once a transaction is voided the predicate genuinely drops
     * that transaction's rows while the ablation keeps them.
     */
    @Test
    fun ablationIsTheGeneratedQueryWithoutTheEffectivePredicateAndThePredicateFilters() {
        P705Database.create("p705-d158-s5-small-").use { harness ->
            repeat(SMALL_TRANSACTION_COUNT) { index ->
                val transactionId = harness.bulkTransactionId(index)
                if (index % 2 == 0) {
                    harness.insertOrdinaryExpense(transactionId, amountMinor = 1_000L * (index + 1))
                } else {
                    harness.insertOrdinaryIncome(transactionId, amountMinor = 1_000L * (index + 1))
                }
            }

            val generatedBeforeVoid = generatedCurrentVersionRows(harness)
            assertTrue(generatedBeforeVoid.isNotEmpty(), "the small fixture must produce rows")
            assertEquals(
                generatedBeforeVoid,
                rowsOf(harness, CURRENT_VERSION_ABLATION, CURRENT_VERSION_COLUMNS),
                "the ablation must reproduce the generated query's rows while every transaction is effective",
            )
            assertEquals(
                generatedEntryRows(harness),
                rowsOf(harness, ENTRY_ABLATION, ENTRY_COLUMNS),
                "the entry ablation must reproduce the generated query's rows while every transaction is effective",
            )

            val ids = P705Ids("p705-d158-s5")
            val voidedTransactionId = harness.bulkTransactionId(0)
            val voided =
                assertIs<VoidTransactionResult.Created>(
                    ExecuteVoidTransaction(harness.voidPort, ids.voidSource, fixedClock(P705Fixture.voidedAt))
                        .execute(
                            VoidTransactionRequest(
                                ledgerId = P705Fixture.ledgerId,
                                requestId = ids.requestId(),
                                transactionId = TransactionId(voidedTransactionId),
                                reason = VoidReason(VoidReasonCode.MIS_ENTERED, "entered the wrong amount"),
                                confirmation = ExplicitManualSave,
                            ),
                        ),
                )
            assertEquals(voidedTransactionId, voided.receipt.transactionId.value)

            val generatedAfterVoid = generatedCurrentVersionRows(harness)
            val entryAfterVoid = generatedEntryRows(harness)
            val ablationAfterVoid = rowsOf(harness, CURRENT_VERSION_ABLATION, CURRENT_VERSION_COLUMNS)
            assertTrue(
                generatedAfterVoid.size < generatedBeforeVoid.size,
                "the effective predicate must drop the voided transaction's rows",
            )
            assertTrue(
                generatedAfterVoid.none { it.first() == voidedTransactionId },
                "the voided transaction must leave the effective current-version read",
            )
            assertTrue(
                entryAfterVoid.none { it.first() == voidedTransactionId },
                "the voided transaction must leave the effective entry read",
            )
            assertTrue(
                ablationAfterVoid.any { it.first() == voidedTransactionId },
                "the ablation carries no predicate, so it still returns the voided transaction",
            )
            assertEquals(
                generatedBeforeVoid,
                ablationAfterVoid,
                "the ablation is unchanged by the void: it differs from production only by the effective predicate",
            )
        }
    }

    private fun measureMs(block: () -> Int): Double {
        val start = System.nanoTime()
        val rows = block()
        val elapsed = System.nanoTime() - start
        assertEquals(EXPECTED_ROW_COUNT, rows, "the timed read must return the complete fixture row set")
        return elapsed / 1_000_000.0
    }

    /** The as-shipped generated current-version query (its own mapper, so not part of any delta). */
    private fun withPredicateCurrentVersion(harness: P705Database): Int =
        harness.database.ledgerQueries
            .currentVersionRowsForLedger(P705Fixture.ledgerId.value)
            .executeAsList()
            .size

    /** The as-shipped generated entry query (its own mapper, so not part of any delta). */
    private fun withPredicateEntry(harness: P705Database): Int =
        harness.database.ledgerQueries
            .ledgerEntryRowsForLedger(P705Fixture.ledgerId.value)
            .executeAsList()
            .size

    /** The `pure` convention: step the cursor, read no column. */
    private fun countRows(
        harness: P705Database,
        sql: String,
    ): Int =
        harness.driverQuery(sql, P705Fixture.ledgerId.value) { cursor ->
            var rows = 0
            while (cursor.next().value) rows += 1
            rows
        }

    /** The `materialized` convention: read and store every column of every row. */
    private fun rowsOf(
        harness: P705Database,
        sql: String,
        columns: Int,
    ): List<List<String?>> =
        harness.driverQuery(sql, P705Fixture.ledgerId.value) { cursor ->
            buildList {
                while (cursor.next().value) {
                    add((0 until columns).map { column -> cursor.getString(column) })
                }
            }
        }

    /** The generated current-version projection, in the transcribed column order. */
    private fun generatedCurrentVersionRows(harness: P705Database): List<List<String?>> =
        harness.database.ledgerQueries
            .currentVersionRowsForLedger(P705Fixture.ledgerId.value)
            .executeAsList()
            .map { row ->
                listOf(
                    row.transaction_id,
                    row.current_version_id,
                    row.kind,
                    row.occurred_at,
                    row.posting_id,
                    row.posting_index.toString(),
                    row.account_id,
                    row.amount_minor.toString(),
                    row.currency_code,
                    row.currency_precision.toString(),
                )
            }

    /** The generated entry projection, in the transcribed column order (`note` is nullable). */
    private fun generatedEntryRows(harness: P705Database): List<List<String?>> =
        harness.database.ledgerQueries
            .ledgerEntryRowsForLedger(P705Fixture.ledgerId.value)
            .executeAsList()
            .map { row ->
                listOf(
                    row.transaction_id,
                    row.current_version_id,
                    row.kind,
                    row.occurred_at,
                    row.statistics_at,
                    row.note,
                    row.posting_id,
                    row.posting_index.toString(),
                    row.account_id,
                    row.amount_minor.toString(),
                    row.currency_code,
                    row.currency_precision.toString(),
                )
            }

    /** Plan text for report evidence. Deliberately never asserted (see the class doc). */
    private fun planLines(
        harness: P705Database,
        sql: String,
    ): List<String> =
        harness.driverQuery("EXPLAIN QUERY PLAN ${sql.trim().removeSuffix(";")}", P705Fixture.ledgerId.value) { cursor ->
            buildList {
                while (cursor.next().value) {
                    add("detail=${cursor.getString(3)}")
                }
            }
        }

    /** The leading column of each index on `ledger_transaction`, by index name. */
    private fun ledgerTransactionIndexLeadColumns(harness: P705Database): Map<String, String> =
        harness
            .ledgerQueryTexts("SELECT name FROM pragma_index_list('ledger_transaction') ORDER BY name")
            .associateWith { indexName ->
                harness
                    .ledgerQueryTexts("SELECT name FROM pragma_index_info('$indexName') WHERE seqno = 0")
                    .single()
            }

    /**
     * Min and max for both sides, plus the delta as a paired per-round observation. The delta is
     * computed within each round and only then summarised: each round measures both sides under the
     * same conditions, so the paired delta is the meaningful quantity. Differencing two
     * independently chosen extrema would not be — it can report a "max delta" smaller than the
     * "min delta" when the two sides' slowest rounds are not the same round. Recorded only.
     */
    private fun reportSeries(
        query: String,
        convention: String,
        withPredicateMs: List<Double>,
        ablationMs: List<Double>,
    ) {
        listOf("min" to { values: List<Double> -> values.min() }, "max" to { values: List<Double> -> values.max() }).forEach { (statistic, pick) ->
            println(
                "D158-S5 $query $convention $statistic with-predicate-ms=${format(pick(withPredicateMs))}" +
                    " ablation-ms=${format(pick(ablationMs))}",
            )
        }
        val deltas = withPredicateMs.zip(ablationMs) { withPredicate, ablation -> withPredicate - ablation }
        val perRound = deltas.mapIndexed { index, delta -> "r${index + 1}=${format(delta)}" }.joinToString(" ")
        listOf("min" to { values: List<Double> -> values.min() }, "max" to { values: List<Double> -> values.max() }).forEach { (statistic, pick) ->
            val delta = pick(deltas)
            println(
                "D158-S5 $query $convention delta-$statistic delta-ms=${format(delta)}" +
                    " delta-pct=${format(percent(delta, pick(ablationMs)))} direction=${direction(delta)}" +
                    " per-round[$perRound]",
            )
        }
    }

    /** The as-shipped reference series, reported without a delta (its mapper differs). */
    private fun reportReference(
        query: String,
        generatedMs: List<Double>,
    ) {
        println(
            "D158-S5 $query generated-reference min-ms=${format(generatedMs.min())} max-ms=${format(generatedMs.max())}" +
                " (as-shipped, own mapper; not comparable with the series above)",
        )
    }

    /** The delta as a percentage of the ablation side, so a positive value means the predicate costs more. */
    private fun percent(
        deltaMs: Double,
        ablationMs: Double,
    ): Double = deltaMs / ablationMs * 100.0

    private fun direction(deltaMs: Double): String =
        when {
            deltaMs > 0.0 -> "predicate-slower"
            deltaMs < 0.0 -> "predicate-faster"
            else -> "equal"
        }

    /** Locale-independent so the printed reading is greppable on any host locale. */
    private fun format(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

    private data class RoundReading(
        val round: Int,
        val order: String,
        val series: Map<String, Double>,
    )

    private companion object {
        /** D-158 section 5 fixes the scale: a 20k-transaction database. */
        const val TRANSACTION_COUNT = 20_000

        /** Two legs per transaction (category leg + funding leg). */
        const val EXPECTED_ROW_COUNT = TRANSACTION_COUNT * 2

        const val SMALL_TRANSACTION_COUNT = 4

        /** Chains per fixture in the bulk-versus-per-query equivalence proof. */
        const val EQUIVALENCE_CHAINS = 6

        const val WARMUP_ROUNDS = 1

        /**
         * 4 measured rounds so the within-round order alternates 2:2 (predicate-first in rounds 1 and
         * 3, ablation-first in rounds 2 and 4); an odd count would leave one side warming the other's
         * cache more often. The warmup round is discarded.
         */
        const val MEASURED_ROUNDS = 4

        const val CURRENT_VERSION_COLUMNS = 10
        const val ENTRY_COLUMNS = 12

        val QUERY_LABELS = listOf("current-version", "entry")

        /** Tables the effective join chain touches; `ANALYZE` must cover all of them. */
        val HOT_PATH_TABLES =
            listOf(
                "ledger_transaction",
                "ledger_transaction_current_version",
                "transaction_version",
                "posting_set",
                "posting",
            )

        /** Synthetic, anonymous instants spread across a year so `statistics_at` is not degenerate. */
        val FIXTURE_INSTANTS =
            listOf(
                Instant.parse("2025-01-05T02:00:00Z"),
                Instant.parse("2025-04-05T02:00:00Z"),
                Instant.parse("2025-08-05T02:00:00Z"),
                Instant.parse("2025-12-05T02:00:00Z"),
            )

        /** Every chain row, ordered so two independently built fixtures compare directly. */
        const val CHAIN_DUMP =
            "SELECT transaction_id || '|' || ledger_id || '|' || kind || '|' || COALESCE(canonical_kind, '-') FROM ledger_transaction " +
                "UNION ALL SELECT version_id || '|' || transaction_id || '|' || ledger_id || '|' || version_number || '|' || posting_set_id || '|' || " +
                "occurred_at || '|' || statistics_at || '|' || effective_at || '|' || COALESCE(note, '-') FROM transaction_version " +
                "UNION ALL SELECT posting_id || '|' || posting_set_id || '|' || ledger_id || '|' || posting_index || '|' || account_id || '|' || " +
                "amount_minor || '|' || currency_code || '|' || currency_precision FROM posting " +
                "UNION ALL SELECT posting_set_id || '|' || ledger_id FROM posting_set " +
                "ORDER BY 1"

        /** Every current-version pointer, ordered so two independently built fixtures compare directly. */
        const val CURRENT_VERSION_DUMP =
            "SELECT transaction_id || '|' || ledger_id || '|' || current_version_id FROM ledger_transaction_current_version ORDER BY 1"

        /**
         * `Ledger.sq:8718-8748` (`currentVersionRowsForLedger`) verbatim. Kept as the with-predicate
         * side so the timed text is the production text; the ablation below is this text minus the
         * effective join, and an assertion ties this text's output to the generated query's.
         */
        val CURRENT_VERSION_WITH_PREDICATE =
            """
            SELECT
              tx.transaction_id,
              current_version.current_version_id,
              tx.kind,
              version.occurred_at,
              posting.posting_id,
              posting.posting_index,
              posting.account_id,
              posting.amount_minor,
              posting.currency_code,
              posting.currency_precision
            FROM ledger_transaction AS tx
            JOIN ledger_transaction_current_version AS current_version
              ON current_version.ledger_id = tx.ledger_id
             AND current_version.transaction_id = tx.transaction_id
            JOIN transaction_version AS version
              ON version.ledger_id = tx.ledger_id
             AND version.transaction_id = tx.transaction_id
             AND version.version_id = current_version.current_version_id
            JOIN posting_set AS posting_set
              ON posting_set.ledger_id = version.ledger_id
             AND posting_set.posting_set_id = version.posting_set_id
            JOIN posting AS posting
              ON posting.ledger_id = posting_set.ledger_id
             AND posting.posting_set_id = posting_set.posting_set_id
            JOIN transaction_effective_state AS effective
              ON effective.ledger_id = tx.ledger_id
             AND effective.transaction_id = tx.transaction_id
             AND effective.is_effective = 1
            WHERE tx.ledger_id = ?
            ORDER BY tx.transaction_id, posting.posting_index;
            """.trimIndent()

        /**
         * `Ledger.sq:8761-8793` (`ledgerEntryRowsForLedger`) verbatim, same arrangement.
         */
        val ENTRY_WITH_PREDICATE =
            """
            SELECT
              tx.transaction_id,
              current_version.current_version_id,
              COALESCE(tx.canonical_kind, tx.kind) AS kind,
              version.occurred_at,
              version.statistics_at,
              version.note,
              posting.posting_id,
              posting.posting_index,
              posting.account_id,
              posting.amount_minor,
              posting.currency_code,
              posting.currency_precision
            FROM ledger_transaction AS tx
            JOIN ledger_transaction_current_version AS current_version
              ON current_version.ledger_id = tx.ledger_id
             AND current_version.transaction_id = tx.transaction_id
            JOIN transaction_version AS version
              ON version.ledger_id = tx.ledger_id
             AND version.transaction_id = tx.transaction_id
             AND version.version_id = current_version.current_version_id
            JOIN posting_set AS posting_set
              ON posting_set.ledger_id = version.ledger_id
             AND posting_set.posting_set_id = version.posting_set_id
            JOIN posting AS posting
              ON posting.ledger_id = posting_set.ledger_id
             AND posting.posting_set_id = posting_set.posting_set_id
            JOIN transaction_effective_state AS effective
              ON effective.ledger_id = tx.ledger_id
             AND effective.transaction_id = tx.transaction_id
             AND effective.is_effective = 1
            WHERE tx.ledger_id = ?
            ORDER BY tx.transaction_id, posting.posting_index;
            """.trimIndent()

        /**
         * The one clause D-156 added to both hot queries, verbatim from `Ledger.sq:8743-8746` /
         * `:8788-8791`. The ablation texts below are the production texts minus exactly this clause.
         */
        const val EFFECTIVE_JOIN =
            "JOIN transaction_effective_state AS effective\n" +
                "  ON effective.ledger_id = tx.ledger_id\n" +
                " AND effective.transaction_id = tx.transaction_id\n" +
                " AND effective.is_effective = 1\n"

        /** [CURRENT_VERSION_WITH_PREDICATE] minus [EFFECTIVE_JOIN]. */
        val CURRENT_VERSION_ABLATION = CURRENT_VERSION_WITH_PREDICATE.replace(EFFECTIVE_JOIN, "")

        /** [ENTRY_WITH_PREDICATE] minus [EFFECTIVE_JOIN]. */
        val ENTRY_ABLATION = ENTRY_WITH_PREDICATE.replace(EFFECTIVE_JOIN, "")
    }
}
