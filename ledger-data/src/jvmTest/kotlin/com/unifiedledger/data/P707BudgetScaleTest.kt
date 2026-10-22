package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.BudgetCommandResult
import com.unifiedledger.application.BudgetIdSource
import com.unifiedledger.application.BudgetMonth
import com.unifiedledger.application.BudgetMonthConfigReader
import com.unifiedledger.application.BudgetMonthResult
import com.unifiedledger.application.BudgetMonthView
import com.unifiedledger.application.BudgetMonthViewResult
import com.unifiedledger.application.BudgetRequestId
import com.unifiedledger.application.BudgetRequestIdSource
import com.unifiedledger.application.ExecuteVoidTransaction
import com.unifiedledger.application.ExplicitManualSave
import com.unifiedledger.application.LedgerEntryRow
import com.unifiedledger.application.MonthlyBuckets
import com.unifiedledger.application.MonthlyContributionReadPort
import com.unifiedledger.application.MonthlyContributionReadResult
import com.unifiedledger.application.QueryBudgetMonth
import com.unifiedledger.application.SaveBudgetConfiguration
import com.unifiedledger.application.VoidTransactionRequest
import com.unifiedledger.application.VoidTransactionResult
import com.unifiedledger.application.budgetMonthConfigKey
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.BudgetId
import com.unifiedledger.domain.BudgetScope
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.VoidReason
import com.unifiedledger.domain.VoidReasonCode
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.YearMonth
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-07 P707-A08 scale reading (plan section 7.3 line 253; D-237 item 4; D-186): 20,000 and
 * 50,000 ordinary formal transactions spread over three Asia/Shanghai statistics months, with
 * one third of the transactions carrying a SECOND version (the version-1 row retained, the
 * current pointer moved to version 2), a voided subset in every tested month, a
 * parent/child-a/child-b category tree plus an unmapped EXPENSE account (无分类), and real
 * budget configuration written through [SaveBudgetConfiguration]. Every read runs through the
 * real bounded contribution adapter, [SqlDelightBudgetStore] and [QueryBudgetMonth.queryView]
 * on a SECOND file connection wrapped by [CountingSqlDriver].
 *
 * DISCRIMINATING PROPERTIES (each one makes at least one assertion below fail when broken):
 *  - The current-version join: every corrected transaction keeps its version-1 row in the SAME
 *    month with a DIFFERENT amount. Dropping the `ledger_transaction_current_version` join would
 *    count both versions (the exact per-scope net expense would grow by the retained version-1
 *    amount, and a returned row would carry four postings instead of two).
 *  - The effective predicate: a voided subset exists in every tested month (one voided EXPENSE
 *    per month, each in a different scope). Dropping `transaction_effective_state` would count
 *    those amounts again.
 *  - One bounded read per view: a per-scope re-read would raise the read-port call count above
 *    one and the measured SQL query count above the single-observation view's count.
 *
 * RECORDED, NOT THRESHOLD-ASSERTED: every figure printed under the `P707-A08 ` prefix (fixture
 * build time, probe-surface rows, window current-version/voided rows, returned entry rows, SQL
 * query count, JVM heap peak, connection-hold and end-to-end view time) and the environment
 * identity lines (D-158 section 5 convention). There is no wall-clock threshold; the heap peak
 * is the JVM heap only and says nothing about a device ANR/OOM. Two figures are additionally
 * pinned structurally so the measurement cannot silently report a wrong surface: the
 * probe-surface row count equals the fixture's version-row total, and the SQL count is non-zero
 * (the counting driver really observed the read). The measured values live in the JUnit XML's
 * system-out, not in this class.
 *
 * All ids, accounts, categories, amounts and instants are anonymous synthetic values; amounts
 * are exact integer minor units and are folded from the manifest in Kotlin (never SQL SUM or
 * DISTINCT).
 */
class P707BudgetScaleTest {
    @Test
    fun budgetMonthScaleOnTwentyThousandTransactions() {
        assertBudgetMonthScale(TWENTY_THOUSAND)
    }

    @Test
    fun budgetMonthScaleOnFiftyThousandTransactions() {
        assertBudgetMonthScale(FIFTY_THOUSAND)
    }

    private fun assertBudgetMonthScale(count: Int) {
        val manifests = buildManifest(count)
        P705Database.create("p707-a08-$count-").use { harness ->
            val catalogStore = SqlDelightCatalogStore.forPlatformConfiguredDatabase(harness.database)
            val buildStart = System.nanoTime()
            buildScaleFixture(harness, count)
            assertIs<CatalogBootstrapResult.Seeded>(catalogStore.bootstrap(P705Fixture.ledgerId, scaleCatalogSeed()))
            val buildNanos = System.nanoTime() - buildStart

            // The fixture premise every expectation below is derived from.
            assertEquals(count.toLong(), harness.ledgerQueryCount("SELECT count(*) FROM ledger_transaction"))
            assertEquals(
                (count + correctedCount(count)).toLong(),
                harness.ledgerQueryCount("SELECT count(*) FROM transaction_version"),
                "every transaction keeps version 1 and the corrected third gains version 2",
            )
            assertEquals(
                (2L * count + 2L * correctedCount(count)),
                harness.ledgerQueryCount("SELECT count(*) FROM posting"),
                "each version contributes exactly its own two postings",
            )
            assertEquals(
                0L,
                harness.ledgerQueryCount("SELECT count(*) FROM transaction_version WHERE statistics_at_epoch_nanos IS NULL"),
                "the missing-projection probe must see a fully projected ledger",
            )
            assertEquals(VOIDED_INDICES.size.toLong(), harness.ledgerQueryCount("SELECT count(*) FROM transaction_void_fact"))
            assertEquals(
                correctedCount(count).toLong(),
                harness.ledgerQueryCount(
                    "SELECT count(*) FROM ledger_transaction_current_version WHERE current_version_id LIKE '%-version-2'",
                ),
            )

            val budgetStore = SqlDelightBudgetStore.forPlatformConfiguredDatabase(harness.database)
            val commands = ScaleBudgetCommands(budgetStore, catalogStore)
            val march = manifests[MARCH_ORDINAL]

            // Phase A: TOTAL only. The limit is derived from the manifest so remaining and
            // overspent are exact, assertable integers.
            val totalLimit = march.totalNetMinorUnits + TOTAL_HEADROOM
            assertIs<BudgetCommandResult.Accepted>(commands.setLimit(MARCH, BudgetScope.Total, totalLimit, expectedRevision = 0L))

            openCountingSurface(harness).use { surface ->
                val probeSurfaceRows =
                    queryLong(
                        surface.countingDriver,
                        "SELECT count(*) FROM transaction_version WHERE ledger_id = '${P705Fixture.ledgerId.value}'",
                    )
                surface.countingDriver.resetCounts()
                assertEquals(
                    (count + correctedCount(count)).toLong(),
                    probeSurfaceRows,
                    "the probe surface is the whole ledger's version rows (version 1 plus the corrected third's version 2)",
                )

                val phaseA = measureView(surface, MARCH)
                assertSingleTotalView(phaseA, march, totalLimit)
                assertReturnedRows(phaseA, march)
                assertTrue(phaseA.sqlQueries > 0, "the counting driver must observe the view's SQL statements")
                // The window's CURRENT-version rows — surface (b) of the three reported row
                // counts — measured on the same connection with the adapter's own window
                // predicate and current-version join, so the figure is not read back off the
                // manifest.
                assertEquals(
                    march.windowCurrentVersionRows.toLong(),
                    phaseA.windowCurrentVersionRows,
                    "the window holds exactly one current-version row per transaction of the month",
                )
                println(
                    "P707-A08 probe-surface-rows=$probeSurfaceRows " +
                        "(whole-ledger transaction_version rows behind the missing-projection probe)",
                )

                // Phase B: the same ledger and the same month gain parent + child-a + child-b.
                val parentLimit = march.parentNetMinorUnits - PARENT_OVERSPENT
                val childALimit = march.childANetMinorUnits + CHILD_A_HEADROOM
                val childBLimit = march.childBNetMinorUnits - CHILD_B_OVERSPENT
                assertAccepted(commands.setLimit(MARCH, BudgetScope.Category(parentCategory), parentLimit, expectedRevision = 0L))
                assertAccepted(commands.setLimit(MARCH, BudgetScope.Category(childACategory), childALimit, expectedRevision = 0L))
                assertAccepted(commands.setLimit(MARCH, BudgetScope.Category(childBCategory), childBLimit, expectedRevision = 0L))

                val phaseB = measureView(surface, MARCH)
                assertEquals(4, phaseB.view.observations.size, "TOTAL, parent, child-a and child-b must each be observed")
                assertEquals(
                    listOf(
                        BudgetScope.Total,
                        BudgetScope.Category(childACategory),
                        BudgetScope.Category(childBCategory),
                        BudgetScope.Category(parentCategory),
                    ),
                    phaseB.view.observations.map { it.budgetMonth.scope },
                    "TOTAL first, then the configured category scopes ordered by category id",
                )
                assertObservation(phaseB.view, BudgetScope.Total, totalLimit, march.totalNetMinorUnits)
                assertObservation(phaseB.view, BudgetScope.Category(parentCategory), parentLimit, march.parentNetMinorUnits)
                assertObservation(phaseB.view, BudgetScope.Category(childACategory), childALimit, march.childANetMinorUnits)
                assertObservation(phaseB.view, BudgetScope.Category(childBCategory), childBLimit, march.childBNetMinorUnits)
                assertReturnedRows(phaseB, march)

                // The one-fold contract: N configured scopes still issue ONE read-port call and
                // the SAME SQL statement count as the TOTAL-only view. A per-scope re-read would
                // raise both.
                assertEquals(
                    phaseA.sqlQueries,
                    phaseB.sqlQueries,
                    "adding configured scopes must not add SQL reads (one bounded read per view)",
                )
                assertEquals(phaseA.readCalls, phaseB.readCalls)
                assertEquals(1, phaseB.readCalls)

                // Parent/child observations stay independent: the parent rollup equals its two
                // children, TOTAL includes the unmapped EXPENSE account and is never the sum of
                // the configured observations.
                val totalObservation = observation(phaseB.view, BudgetScope.Total)
                val parentObservation = observation(phaseB.view, BudgetScope.Category(parentCategory))
                val childAObservation = observation(phaseB.view, BudgetScope.Category(childACategory))
                val childBObservation = observation(phaseB.view, BudgetScope.Category(childBCategory))
                assertEquals(
                    childAObservation.netExpenseMinorUnits + childBObservation.netExpenseMinorUnits,
                    parentObservation.netExpenseMinorUnits,
                    "the parent scope observes its children's rollup",
                )
                assertEquals(
                    parentObservation.netExpenseMinorUnits + march.uncategorizedNetMinorUnits,
                    totalObservation.netExpenseMinorUnits,
                    "TOTAL includes 无分类 and is not the configured scopes plus their children",
                )
                assertTrue(march.uncategorizedNetMinorUnits > 0L, "the fixture must place real amount in the unmapped EXPENSE account")

                // The two remaining months carry TOTAL only; each is read separately.
                val april = manifests[APRIL_ORDINAL]
                val may = manifests[MAY_ORDINAL]
                val aprilLimit = april.totalNetMinorUnits + TOTAL_HEADROOM
                val mayLimit = may.totalNetMinorUnits + TOTAL_HEADROOM
                assertAccepted(commands.setLimit(APRIL, BudgetScope.Total, aprilLimit, expectedRevision = 0L))
                assertAccepted(commands.setLimit(MAY, BudgetScope.Total, mayLimit, expectedRevision = 0L))
                val aprilReading = measureView(surface, APRIL)
                val mayReading = measureView(surface, MAY)
                assertSingleTotalView(aprilReading, april, aprilLimit)
                assertReturnedRows(aprilReading, april)
                assertSingleTotalView(mayReading, may, mayLimit)
                assertReturnedRows(mayReading, may)

                println(
                    "P707-A08 build-ms=${millis(buildNanos)} fixture-transactions=$count " +
                        "corrected=${correctedCount(count)} voided=${VOIDED_INDICES.size}",
                )
                printReading("phase-a-total-only", phaseA, march)
                printReading("phase-b-four-scopes", phaseB, march)
                printReading("total-only", aprilReading, april)
                printReading("total-only", mayReading, may)
                harness.environmentIdentity().forEach { println("P707-A08 environment $it") }
            }
        }
    }

    /** A single-shot assertion helper so a rejected budget command cannot be silently ignored. */
    private fun assertAccepted(result: BudgetCommandResult) {
        assertIs<BudgetCommandResult.Accepted>(result)
    }

    private fun assertSingleTotalView(
        reading: ViewReading,
        manifest: MonthManifest,
        limitMinorUnits: Long,
    ) {
        assertEquals(1, reading.view.observations.size, "a TOTAL-only month observes exactly one scope")
        assertTrue(reading.view.categories.isEmpty(), "a TOTAL-only month has no category observation")
        assertObservation(reading.view, BudgetScope.Total, limitMinorUnits, manifest.totalNetMinorUnits)
    }

    private fun assertObservation(
        view: BudgetMonthView,
        scope: BudgetScope,
        limitMinorUnits: Long,
        expectedNetMinorUnits: Long,
    ) {
        val observation = observation(view, scope)
        assertEquals(limitMinorUnits, observation.limitMinorUnits, "limit of $scope")
        assertEquals(expectedNetMinorUnits, observation.netExpenseMinorUnits, "net expense of $scope")
        assertEquals(limitMinorUnits - expectedNetMinorUnits, observation.remainingMinorUnits, "remaining of $scope")
        assertEquals(maxOf(expectedNetMinorUnits - limitMinorUnits, 0L), observation.overspentMinorUnits, "overspent of $scope")
    }

    private fun observation(
        view: BudgetMonthView,
        scope: BudgetScope,
    ): BudgetMonth {
        return assertIs<BudgetMonthResult.Success>(view.observations.single { it.budgetMonth.scope == scope }).budgetMonth
    }

    /**
     * Every returned row must be the CURRENT version of an EFFECTIVE transaction of that month,
     * carrying exactly its own two postings (the current-version join is what rules out the
     * retained version-1 postings).
     */
    private fun assertReturnedRows(
        reading: ViewReading,
        manifest: MonthManifest,
    ) {
        assertEquals(
            manifest.effectiveTransactionIds,
            reading.returnedRows.map { it.transactionId.value },
            "the bounded read must return exactly the month's current-version effective transactions",
        )
        assertEquals(
            manifest.effectiveTransactionIds.map { manifest.currentVersionIdsByTransaction.getValue(it) },
            reading.returnedRows.map { it.currentVersionId.value },
            "each returned row must carry the transaction's current version",
        )
        reading.returnedRows.forEach { row ->
            assertEquals(2, row.postings.size, "a current version contributes exactly its own two postings")
        }
        // The manifest's per-account counts are the fixture's posting re-attribution, asserted
        // against the returned current versions rather than the raw rows.
        val expenseRows = reading.returnedRows.filter { it.kind == TransactionKind.EXPENSE }
        assertEquals(
            manifest.childATransactionCount,
            expenseRows.count { it.postings.first().accountId == P705Fixture.expenseAccount },
            "child-a's posting account carries its manifest count",
        )
        assertEquals(
            manifest.childBTransactionCount,
            expenseRows.count { it.postings.first().accountId == secondExpenseAccount },
            "child-b's posting account carries its manifest count",
        )
        assertEquals(
            manifest.uncategorizedTransactionCount,
            expenseRows.count { it.postings.first().accountId == unmappedExpenseAccount },
            "the unmapped EXPENSE account carries its manifest count",
        )
        assertEquals(
            manifest.effectiveExpenseTransactionCount,
            expenseRows.size,
            "every effective EXPENSE row belongs to exactly one scope account",
        )
    }

    private fun measureView(
        surface: CountingBudgetSurface,
        month: YearMonth,
    ): ViewReading {
        surface.countingDriver.resetCounts()
        surface.readPort.reset()
        val sampler = JvmHeapPeakSampler.startSampling()
        val started = System.nanoTime()
        var elapsedNanos = 0L
        var peakHeapBytes = 0L
        val result =
            try {
                surface.query.queryView(P705Fixture.ledgerId, month, surface.catalogVersion)
            } finally {
                elapsedNanos = System.nanoTime() - started
                peakHeapBytes = sampler.stop()
            }
        assertEquals(1, surface.readPort.callCount, "the bounded contribution read must be issued exactly once per view")
        // Read the armed statement count BEFORE the window-row probe below, so the probe cannot
        // inflate the figure the phase comparison relies on.
        val sqlQueries = surface.countingDriver.queryCount
        return ViewReading(
            view = assertIs<BudgetMonthViewResult.Success>(result).view,
            sqlQueries = sqlQueries,
            readCalls = surface.readPort.callCount,
            returnedRows = surface.readPort.lastRows,
            connectionHoldNanos = surface.readPort.lastHoldNanos,
            queryViewNanos = elapsedNanos,
            peakHeapBytes = peakHeapBytes,
            windowCurrentVersionRows = countWindowCurrentVersionRows(surface.countingDriver, month),
        )
    }

    private fun printReading(
        leg: String,
        reading: ViewReading,
        manifest: MonthManifest,
    ) {
        println(
            "P707-A08 month=${manifest.month} leg=$leg observations=${reading.view.observations.size}" +
                " window-current-version-rows=${reading.windowCurrentVersionRows}" +
                " window-voided-rows=${manifest.voidedRows}" +
                " returned-ledger-entry-rows=${reading.returnedRows.size}" +
                " sql-queries=${reading.sqlQueries}" +
                " connection-hold-ms=${millis(reading.connectionHoldNanos)}" +
                " query-view-ms=${millis(reading.queryViewNanos)}" +
                " peak-heap-bytes=${reading.peakHeapBytes} (jvm-heap-only)",
        )
    }

    private fun openCountingSurface(harness: P705Database): CountingBudgetSurface {
        return CountingBudgetSurface(JdbcSqliteDriver("jdbc:sqlite:${harness.filePath}"))
    }

    /**
     * Surface (b) of the three reported row counts: how many `transaction_version` rows of the
     * tested month survive the adapter's own window predicate AND its current-version join.
     * This is the row count the bounded statement can return before posting fan-out; it is
     * counted with the same two predicates the product SQL uses, so a manifest figure cannot
     * mask a real regression.
     */
    private fun countWindowCurrentVersionRows(
        driver: SqlDriver,
        month: YearMonth,
    ): Long {
        val start = MonthlyBuckets.monthStart(month)
        val end = MonthlyBuckets.monthStart(month.plus(1, DateTimeUnit.MONTH))
        return queryLong(
            driver,
            "SELECT count(*) FROM transaction_version AS version " +
                "JOIN ledger_transaction_current_version AS current_version " +
                "ON current_version.ledger_id = version.ledger_id " +
                "AND current_version.transaction_id = version.transaction_id " +
                "AND current_version.current_version_id = version.version_id " +
                "WHERE version.ledger_id = '${P705Fixture.ledgerId.value}' " +
                "AND version.statistics_at_epoch_nanos >= ${epochNanos(start)} " +
                "AND version.statistics_at_epoch_nanos < ${epochNanos(end)}",
        )
    }

    // ------------------------------------------------------------------ construction manifest

    /**
     * The manifest of one count: which transactions land in which month, which version is
     * current, which are voided, and the exact per-scope net expense. Derived in Kotlin from the
     * fixture formulas only, so the read side cannot define its own oracle.
     */
    private fun buildManifest(count: Int): List<MonthManifest> =
        TESTED_MONTHS.mapIndexed { ordinal, month ->
            val transactionIndices = (0 until count).filter { monthOrdinal(it) == ordinal }
            val effectiveIndices = transactionIndices.filter { it !in VOIDED_INDICES }
            val effectiveExpenseIndices = effectiveIndices.filter { it % 2 == 0 }
            MonthManifest(
                month = month,
                currentVersionIdsByTransaction = transactionIndices.associate { transactionIdOf(it) to versionIdOf(it) },
                effectiveTransactionIds = effectiveIndices.map(::transactionIdOf),
                windowCurrentVersionRows = transactionIndices.size,
                voidedRows = transactionIndices.count { it in VOIDED_INDICES },
                childATransactionCount = effectiveExpenseIndices.count { (it / 9) % 3 == 0 },
                childBTransactionCount = effectiveExpenseIndices.count { (it / 9) % 3 == 1 },
                uncategorizedTransactionCount = effectiveExpenseIndices.count { (it / 9) % 3 == 2 },
                totalNetMinorUnits = effectiveExpenseIndices.sumOf(::currentAmountMinor),
                childANetMinorUnits = effectiveExpenseIndices.filter { (it / 9) % 3 == 0 }.sumOf(::currentAmountMinor),
                childBNetMinorUnits = effectiveExpenseIndices.filter { (it / 9) % 3 == 1 }.sumOf(::currentAmountMinor),
                uncategorizedNetMinorUnits = effectiveExpenseIndices.filter { (it / 9) % 3 == 2 }.sumOf(::currentAmountMinor),
            )
        }

    // ------------------------------------------------------------------ fixture

    private fun buildScaleFixture(
        harness: P705Database,
        count: Int,
    ) {
        harness.insertOrdinaryBulk(
            count = count,
            statisticsAt = { index -> MONTH_INSTANTS[monthOrdinal(index)] },
        )
        val corrected = (0 until count).filter(::isCorrected)
        val ledger = P705Fixture.ledgerId.value
        harness.database.transaction {
            fillStatisticsProjection(harness)
            insertChunked(
                harness,
                corrected.size,
                "INSERT INTO transaction_version " +
                    "(version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, " +
                    "effective_at, note, statistics_at_epoch_nanos) VALUES ",
            ) { ordinal ->
                val index = corrected[ordinal]
                val instant = MONTH_INSTANTS[monthOrdinal(index)]
                "('${versionIdOf(index)}', '${transactionIdOf(index)}', '$ledger', 2, " +
                    "'${postingSetIdOf(index, 2)}', '$instant', '$instant', '$instant', NULL, ${epochNanos(instant)})"
            }
            insertChunked(
                harness,
                corrected.size,
                "INSERT INTO posting_set(posting_set_id, ledger_id) VALUES ",
            ) { ordinal ->
                "('${postingSetIdOf(corrected[ordinal], 2)}', '$ledger')"
            }
            insertChunked(
                harness,
                corrected.size * POSTINGS_PER_CHAIN,
                "INSERT INTO posting " +
                    "(posting_id, posting_set_id, ledger_id, posting_index, account_id, amount_minor, " +
                    "currency_code, currency_precision) VALUES ",
            ) { postingOrdinal ->
                val index = corrected[postingOrdinal / POSTINGS_PER_CHAIN]
                val leg = postingOrdinal % POSTINGS_PER_CHAIN
                val amount = v2AmountMinor(index)
                val expense = index % 2 == 0
                val account =
                    when {
                        expense && leg == 0 -> expenseAccountFor(index)
                        expense -> P705Fixture.bankA
                        leg == 0 -> P705Fixture.bankA
                        else -> P705Fixture.incomeAccount
                    }
                val signedAmount = if (leg == 0) amount else -amount
                "('${postingIdOf(index, 2, leg)}', '${postingSetIdOf(index, 2)}', '$ledger', $leg, " +
                    "'${account.value}', $signedAmount, '${P705Fixture.cny.code}', ${P705Fixture.cny.precision})"
            }
            // Move the current pointer of the corrected third to version 2; version 1 stays.
            harness.driverExecute(
                "UPDATE ledger_transaction_current_version " +
                    "SET current_version_id = transaction_id || '-version-2' " +
                    "WHERE ledger_id = '$ledger' " +
                    "AND (CAST(substr(transaction_id, 4) AS INTEGER) - 1) % 9 < 3",
            )
            // The bulk writer puts every EXPENSE category leg on the one fixture expense account.
            // Re-attribute the current version-1 legs of the uncorrected expenses so the fixture
            // exercises a parent with two children plus a genuinely unmapped EXPENSE account.
            harness.driverExecute(
                "UPDATE posting SET account_id = CASE " +
                    "WHEN (((CAST(substr(posting_set_id, 4, 5) AS INTEGER) - 1) / 9) % 3) = 1 THEN '${secondExpenseAccount.value}' " +
                    "WHEN (((CAST(substr(posting_set_id, 4, 5) AS INTEGER) - 1) / 9) % 3) = 2 THEN '${unmappedExpenseAccount.value}' " +
                    "ELSE account_id END " +
                    "WHERE ledger_id = '$ledger' AND posting_index = 0 " +
                    "AND posting_set_id LIKE 'tx-%-posting-set-1' " +
                    "AND (CAST(substr(posting_set_id, 4, 5) AS INTEGER) - 1) % 2 = 0 " +
                    "AND (CAST(substr(posting_set_id, 4, 5) AS INTEGER) - 1) % 9 >= 3",
            )
        }
        // Void the subset through the real product use case, so the effective predicate is
        // exercised against fact rows the commit port actually wrote (the raw insert would have
        // to re-create the per-transaction sequence guard's bookkeeping by hand).
        val voidIds = P705Ids("p707-a08-void")
        val voidTransaction = ExecuteVoidTransaction(harness.voidPort, voidIds.voidSource, fixedClock(P705Fixture.voidedAt))
        VOIDED_INDICES.sorted().forEach { index ->
            val outcome =
                voidTransaction.execute(
                    VoidTransactionRequest(
                        ledgerId = P705Fixture.ledgerId,
                        requestId = voidIds.requestId(),
                        transactionId = TransactionId(transactionIdOf(index)),
                        reason = VoidReason(VoidReasonCode.MIS_ENTERED, "scale fixture void"),
                        confirmation = ExplicitManualSave,
                    ),
                )
            check(outcome is VoidTransactionResult.Created) { "the fixture void must be accepted, got $outcome" }
        }
    }

    /** Fills the numeric time projection of every bulk-written version row. */
    private fun fillStatisticsProjection(harness: P705Database) {
        MONTH_INSTANTS.forEach { instant ->
            harness.driverExecute(
                "UPDATE transaction_version SET statistics_at_epoch_nanos = ${epochNanos(instant)} " +
                    "WHERE statistics_at = '$instant' AND statistics_at_epoch_nanos IS NULL",
            )
        }
    }

    /** Chunked multi-row statement (the P705 bulk fixture's 500-row batching shape). */
    private fun insertChunked(
        harness: P705Database,
        count: Int,
        statementPrefix: String,
        row: (Int) -> String,
    ) {
        var start = 0
        while (start < count) {
            val end = minOf(start + BULK_CHUNK, count)
            harness.driverExecute(statementPrefix + (start until end).joinToString(", ") { row(it) })
            start = end
        }
    }

    private fun scaleCatalogSeed(): CatalogSeed =
        CatalogSeed(
            accounts =
                listOf(
                    CatalogSeedAccount(P705Fixture.bankA, "银行A", AccountKind.ASSET, P705Fixture.cny, ownedByUser = true, realAccount = true, hidden = false),
                    CatalogSeedAccount(P705Fixture.incomeAccount, "收入", AccountKind.INCOME, P705Fixture.cny, ownedByUser = false, realAccount = false, hidden = true),
                    CatalogSeedAccount(P705Fixture.expenseAccount, "餐饮-午餐", AccountKind.EXPENSE, P705Fixture.cny, ownedByUser = false, realAccount = false, hidden = true),
                    CatalogSeedAccount(secondExpenseAccount, "餐饮-外食", AccountKind.EXPENSE, P705Fixture.cny, ownedByUser = false, realAccount = false, hidden = true),
                    CatalogSeedAccount(unmappedExpenseAccount, "未映射支出", AccountKind.EXPENSE, P705Fixture.cny, ownedByUser = false, realAccount = false, hidden = true),
                ),
            categories =
                listOf(
                    CatalogSeedCategory(parentCategory, "餐饮", CategoryKind.EXPENSE, parentId = null, postingAccountId = null),
                    CatalogSeedCategory(childACategory, "餐饮-午餐", CategoryKind.EXPENSE, parentCategory, P705Fixture.expenseAccount),
                    CatalogSeedCategory(childBCategory, "餐饮-外食", CategoryKind.EXPENSE, parentCategory, secondExpenseAccount),
                ),
        )

    /** The real budget command surface over the fixture connection (save configuration only). */
    private class ScaleBudgetCommands(
        commitPort: SqlDelightBudgetStore,
        catalogReader: SqlDelightCatalogStore,
    ) {
        private var counter = 0
        private val save =
            SaveBudgetConfiguration(
                commitPort = commitPort,
                requestIdSource = BudgetRequestIdSource { nextRequestId() },
                budgetIdSource = BudgetIdSource { nextBudgetId() },
                catalogReader = catalogReader,
                clock = fixedClock(P705Fixture.marchStatistics),
            )

        fun setLimit(
            month: YearMonth,
            scope: BudgetScope,
            limitMinorUnits: Long,
            expectedRevision: Long,
        ): BudgetCommandResult = save.setLimit(P705Fixture.ledgerId, month, scope, limitMinorUnits, expectedRevision)

        private fun nextRequestId(): BudgetRequestId {
            counter += 1
            return BudgetRequestId("p707-a08-request-$counter")
        }

        private fun nextBudgetId(): BudgetId {
            counter += 1
            return BudgetId("p707-a08-budget-$counter")
        }
    }

    private data class MonthManifest(
        val month: YearMonth,
        val currentVersionIdsByTransaction: Map<String, String>,
        val effectiveTransactionIds: List<String>,
        val windowCurrentVersionRows: Int,
        val voidedRows: Int,
        val childATransactionCount: Int,
        val childBTransactionCount: Int,
        val uncategorizedTransactionCount: Int,
        val totalNetMinorUnits: Long,
        val childANetMinorUnits: Long,
        val childBNetMinorUnits: Long,
        val uncategorizedNetMinorUnits: Long,
    ) {
        val parentNetMinorUnits: Long get() = childANetMinorUnits + childBNetMinorUnits

        val effectiveExpenseTransactionCount: Int
            get() = childATransactionCount + childBTransactionCount + uncategorizedTransactionCount
    }

    private class ViewReading(
        val view: BudgetMonthView,
        val sqlQueries: Int,
        val readCalls: Int,
        val returnedRows: List<LedgerEntryRow>,
        val connectionHoldNanos: Long,
        val queryViewNanos: Long,
        val peakHeapBytes: Long,
        val windowCurrentVersionRows: Long,
    )

    private companion object {
        const val TWENTY_THOUSAND = 20_000
        const val FIFTY_THOUSAND = 50_000
        const val MARCH_ORDINAL = 0
        const val APRIL_ORDINAL = 1
        const val MAY_ORDINAL = 2
        const val POSTINGS_PER_CHAIN = 2
        const val BULK_CHUNK = 500

        /** The exact remaining the TOTAL limits are set above each month's manifest total. */
        const val TOTAL_HEADROOM = 12_345L

        /** The parent limit is set below its manifest net so overspent is a real positive value. */
        const val PARENT_OVERSPENT = 7L
        const val CHILD_A_HEADROOM = 100L
        const val CHILD_B_OVERSPENT = 1L

        val MARCH = YearMonth(2026, 3)
        val APRIL = YearMonth(2026, 4)
        val MAY = YearMonth(2026, 5)
        val TESTED_MONTHS = listOf(MARCH, APRIL, MAY)

        /** Fixed synthetic instants: 10:00 Asia/Shanghai on the 5th of each tested month. */
        val MONTH_INSTANTS =
            listOf(
                Instant.parse("2026-03-05T02:00:00Z"),
                Instant.parse("2026-04-05T02:00:00Z"),
                Instant.parse("2026-05-05T02:00:00Z"),
            )

        /**
         * One voided EXPENSE per tested month (indices 6, 16 and 26) plus one voided income
         * (15, 13, 23). The expense scopes are child-a (March), child-b (April) and 无分类
         * (May), and none of the six is corrected, so the effective-predicate and
         * current-version discriminations stay independent.
         */
        val VOIDED_INDICES = setOf(6, 13, 15, 16, 23, 26)
    }
}

/**
 * Records the bounded read's call count, returned rows and hold time so a `queryView` can be
 * measured from the outside without re-reading the ledger.
 */
internal class RecordingMonthlyContributionReadPort(
    private val delegate: MonthlyContributionReadPort,
) : MonthlyContributionReadPort {
    var callCount: Int = 0
        private set

    var lastRows: List<LedgerEntryRow> = emptyList()
        private set

    var lastHoldNanos: Long = -1L
        private set

    fun reset() {
        callCount = 0
        lastRows = emptyList()
        lastHoldNanos = -1L
    }

    override fun readContributions(
        ledgerId: LedgerId,
        startInclusive: Instant,
        endExclusive: Instant,
        expectedCatalogVersion: Long,
    ): MonthlyContributionReadResult {
        callCount += 1
        val started = System.nanoTime()
        val result = delegate.readContributions(ledgerId, startInclusive, endExclusive, expectedCatalogVersion)
        lastHoldNanos = System.nanoTime() - started
        lastRows = (result as? MonthlyContributionReadResult.Success)?.rows ?: emptyList()
        return result
    }
}

/**
 * The read surface of one measured connection: the real product read path over the counting
 * driver, with the read port wrapped for counting. Closing it closes the underlying JDBC
 * connection explicitly: the file-backed `JdbcSqliteDriver.close()` is a no-op, and on Windows
 * an open handle would make the fixture file undeletable.
 */
internal class CountingBudgetSurface(
    private val fixtureDriver: JdbcSqliteDriver,
) : AutoCloseable {
    val countingDriver: CountingSqlDriver = CountingSqlDriver(fixtureDriver)
    val database: LedgerDatabase = LedgerDatabase(countingDriver)
    val catalogStore: SqlDelightCatalogStore = SqlDelightCatalogStore.forPlatformConfiguredDatabase(database)
    val budgetStore: SqlDelightBudgetStore = SqlDelightBudgetStore(database, countingDriver)
    val readPort: RecordingMonthlyContributionReadPort =
        RecordingMonthlyContributionReadPort(SqlDelightMonthlyContributionReadAdapter(database, catalogStore))
    val query: QueryBudgetMonth =
        QueryBudgetMonth(
            readPort = readPort,
            authorityReader = budgetStore,
            configReader =
                BudgetMonthConfigReader { ledger, month ->
                    budgetStore.configsForMonth(ledger, budgetMonthConfigKey(month))
                },
        )
    val catalogVersion: Long = requireNotNull(catalogStore.load(P705Fixture.ledgerId)).catalogVersion

    override fun close() {
        fixtureDriver.getConnection().close()
        fixtureDriver.close()
    }
}

private val secondExpenseAccount = AccountId("p707-a08-expense-second")

private val unmappedExpenseAccount = AccountId("p707-a08-expense-unmapped")

private val parentCategory = CategoryId("p707-a08-category-parent")

private val childACategory = CategoryId("p707-a08-category-child-a")

private val childBCategory = CategoryId("p707-a08-category-child-b")

/** The fixture's version-1 amount formula (the P705 bulk writer's default shape). */
private fun v1AmountMinor(index: Int): Long = 1_000L + index % 97 * 137L

/** The corrected version's amount: deliberately different, in the SAME month as version 1. */
private fun v2AmountMinor(index: Int): Long = v1AmountMinor(index) + 3_000L

private fun isCorrected(index: Int): Boolean = index % 9 < 3

private fun correctedCount(count: Int): Int = (0 until count).count(::isCorrected)

private fun currentAmountMinor(index: Int): Long = if (isCorrected(index)) v2AmountMinor(index) else v1AmountMinor(index)

/** Three Shanghai statistics months, assigned round-robin by index. */
private fun monthOrdinal(index: Int): Int = index % 3

/** The EXPENSE category leg's account: child-a, child-b or 无分类, rotating independently of month. */
private fun expenseAccountFor(index: Int): AccountId =
    when ((index / 9) % 3) {
        1 -> secondExpenseAccount
        2 -> unmappedExpenseAccount
        else -> P705Fixture.expenseAccount
    }

private fun epochNanos(instant: Instant): Long = instant.toEpochMilliseconds() * 1_000_000L

private fun transactionIdOf(index: Int): String = "tx-${(index + 1).toString().padStart(5, '0')}"

private fun versionIdOf(index: Int): String = "${transactionIdOf(index)}-version-${if (isCorrected(index)) 2 else 1}"

private fun postingSetIdOf(
    index: Int,
    version: Int,
): String = "${transactionIdOf(index)}-posting-set-$version"

private fun postingIdOf(
    index: Int,
    version: Int,
    leg: Int,
): String = "${transactionIdOf(index)}-version-$version-posting-$leg"
