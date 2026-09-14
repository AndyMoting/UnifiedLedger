package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportContentFingerprint
import com.unifiedledger.application.ImportDuplicateCandidateId
import com.unifiedledger.application.ImportDuplicateIntakeIds
import com.unifiedledger.application.ImportEvidenceId
import com.unifiedledger.application.ImportFundingState
import com.unifiedledger.application.ImportIntakeIds
import com.unifiedledger.application.ImportIntakeResult
import com.unifiedledger.application.ImportIntakeSnapshot
import com.unifiedledger.application.ImportRecordKind
import com.unifiedledger.application.ImportRequestId
import com.unifiedledger.application.ImportRequestIdentity
import com.unifiedledger.application.ImportSourceFacts
import com.unifiedledger.application.ImportSourceId
import com.unifiedledger.application.ImportStatusHistoryId
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.LedgerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * P7-04.B D-146 "implementation-batch obligation (2)": the intake demand count
 * `duplicateMatchCountForIntake` and the existing match read `selectDuplicateMatches`
 * (`Ledger.sq:8296-8301`) must agree point by point on the 0 / 1 / multiple match fixtures
 * and on the `statusToken`-null variant of the predicate.
 *
 * The count query deliberately has no `source_id` self-exclusion parameter because it runs
 * inside the winning claim transaction *before* the new source row is inserted; this test
 * pins the equivalence by comparing it against the self-excluding read with a sentinel
 * source id that is not present in the ledger (exactly the store's situation at count time).
 * Anonymous synthetic rows only; no assertion of the store's writes here (those stay the
 * untouched spine oracles).
 *
 * The store-side companion below (AB-BE-SPEC-02/AB-BE-QUAL-03) drives the real
 * [SqlDelightImportSpineStore.commitIntake] with a recording allocateIds lambda and pins
 * that the demand the store passes equals the in-transaction match count on 0/1/N fixtures.
 */
class DuplicateMatchCountEquivalenceDataTest {
    private val ledgerId = LedgerId("ledger-p704-count")
    private val otherLedgerId = LedgerId("ledger-p704-count-other")

    // The tuple the count and the read are asked about; every inserted row varies from it in
    // exactly one dimension unless it is meant to match.
    private val amountMinor = 12_850L
    private val currencyCode = "CNY"
    private val currencyPrecision = 2L
    private val occurredAt = "2026-09-01T08:30:00+08:00"
    private val directionToken = "out"
    private val statusToken = "settled"

    @Test
    fun countEqualsSelectDuplicateMatchesAcrossZeroOneAndManyMatchFixtures() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            requestRow(driver, ledgerId, "req-count-seed")

            // --- 0 matches: an empty ledger agrees (count 0 == read empty).
            assertEquivalent(driver, "ledger empty")

            // A non-settled row and a different-amount row are near-misses: the predicates
            // must agree they are misses, not just agree on empty inputs.
            insertSource(
                driver,
                sourceId = "source-near-funding",
                amount = amountMinor,
                direction = directionToken,
                status = statusToken,
                fundingState = "UNRESOLVED",
                completeness = "valid_complete",
            )
            insertSource(
                driver,
                sourceId = "source-near-amount",
                amount = amountMinor + 1,
                direction = directionToken,
                status = statusToken,
                fundingState = "SETTLED",
                completeness = "valid_complete",
            )
            insertSource(
                driver,
                sourceId = "source-near-incomplete",
                amount = amountMinor,
                direction = directionToken,
                status = statusToken,
                fundingState = "SETTLED",
                completeness = "valid_incomplete",
            )
            insertSource(
                driver,
                sourceId = "source-near-direction",
                amount = amountMinor,
                direction = "in",
                status = statusToken,
                fundingState = "SETTLED",
                completeness = "valid_complete",
            )
            assertEquivalent(driver, "near misses only")

            // --- 1 match.
            insertSource(
                driver,
                sourceId = "source-hit-1",
                amount = amountMinor,
                direction = directionToken,
                status = statusToken,
                fundingState = "SETTLED",
                completeness = "valid_complete",
            )
            assertEquivalent(driver, "single match")

            // --- multiple matches (the D05 concurrent-match case: the count is the demand).
            insertSource(
                driver,
                sourceId = "source-hit-2",
                amount = amountMinor,
                direction = directionToken,
                status = statusToken,
                fundingState = "SETTLED",
                completeness = "valid_complete",
            )
            insertSource(
                driver,
                sourceId = "source-hit-3",
                amount = amountMinor,
                direction = directionToken,
                status = statusToken,
                fundingState = "SETTLED",
                completeness = "valid_complete",
            )
            assertEquivalent(driver, "three matches")

            // Rows on another ledger must never be counted (ledger-scoped, no cross-ledger leak).
            requestRow(driver, otherLedgerId, "req-count-other")
            insertSource(
                driver,
                sourceId = "source-other-ledger",
                amount = amountMinor,
                direction = directionToken,
                status = statusToken,
                fundingState = "SETTLED",
                completeness = "valid_complete",
                ledger = otherLedgerId,
            )
            assertEquivalent(driver, "other-ledger rows excluded")
        } finally {
            driver.close()
        }
    }

    @Test
    fun statusTokenNullVariantOfThePredicateAgreesPointByPoint() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            requestRow(driver, ledgerId, "req-count-null-variant")

            insertSource(
                driver,
                sourceId = "source-settled-1",
                amount = amountMinor,
                direction = directionToken,
                status = statusToken,
                fundingState = "SETTLED",
                completeness = "valid_complete",
            )
            insertSource(
                driver,
                sourceId = "source-settled-2",
                amount = amountMinor,
                direction = directionToken,
                status = statusToken,
                fundingState = "SETTLED",
                completeness = "valid_complete",
            )
            // A non-null `unresolved` status row: both predicates must exclude it identically.
            insertSource(
                driver,
                sourceId = "source-unresolved",
                amount = amountMinor,
                direction = directionToken,
                status = "unresolved",
                fundingState = "SETTLED",
                completeness = "valid_complete",
            )

            // statusToken = null with the frozen nullFlag = 1: the predicate's null branch.
            // Both queries must agree (the settled rows carry a non-null status token).
            assertEquivalent(
                driver,
                "statusToken-null variant",
                queryStatusToken = null,
                nullFlag = 1L,
            )
            // statusToken = "settled" with nullFlag = 0: the concrete-token branch.
            assertEquivalent(driver, "statusToken concrete", queryStatusToken = statusToken, nullFlag = 0L)
            // The frozen 'unresolved' exclusion applies identically on both sides.
            assertEquivalent(driver, "statusToken unresolved", queryStatusToken = "unresolved", nullFlag = 0L)
        } finally {
            driver.close()
        }
    }

    /**
     * AB-BE-SPEC-02/AB-BE-QUAL-03 (store side, R-Q09-2): the demand [SqlDelightImportSpineStore]
     * passes to the allocation callback is the winning claim transaction's own fact. Driving
     * the real store with a recording allocateIds lambda over four sequential same-tuple
     * intakes, the recorded demand must equal the `selectDuplicateMatches` count the
     * transaction sees (0/1/N fixtures: 0 -> 1 -> 2 -> 3) — a constant-demand regression or a
     * count/read divergence fails here, and the store's in-transaction size re-check
     * additionally cross-validates every minted duplicate id group. No spine oracle
     * expectation is touched.
     */
    @Test
    fun storeIntakeDemandEqualsTheInTransactionMatchCount() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val store = SqlDelightImportSpineStore(database, driver)
            val demands = mutableListOf<Int>()

            // Each intake uses a distinct raw identity and request id, but the same frozen
            // business tuple, so every earlier intake becomes an existing exact-tuple match.
            repeat(4) { index ->
                val expectedMatches =
                    database.ledgerQueries
                        .selectDuplicateMatches(
                            ledgerId.value,
                            "source-sentinel-demand-$index",
                            ImportRecordKind.ORDINARY_FLOW_SOURCE.storageValue,
                            ImportRecordKind.ORDINARY_FLOW_SOURCE.contractVersion.toLong(),
                            amountMinor,
                            currencyCode,
                            currencyPrecision,
                            occurredAt,
                            directionToken,
                            statusToken,
                            0L,
                        ).executeAsList()
                        .size

                val facts = facts(amountMinor, directionToken, statusToken)
                val identity = ImportRequestIdentity(ledgerId, ImportRequestId("req-demand-$index"))
                val result =
                    store.commitIntake(
                        identity,
                        ImportIntakeSnapshot(
                            identity = identity,
                            inputRef = "syn-ref-demand",
                            recordOrdinal = index,
                            recordKind = ImportRecordKind.ORDINARY_FLOW_SOURCE,
                            facts = facts,
                            completeness = ImportCompleteness.VALID_COMPLETE,
                            contentHash = ImportContentFingerprint().digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, facts),
                            candidateGeneratedAt = "legacy-intake-v1",
                        ),
                    ) { requiredDuplicateIds ->
                        demands += requiredDuplicateIds
                        ImportIntakeIds(
                            sourceId = ImportSourceId("source-demand-$index"),
                            evidenceId = ImportEvidenceId("evidence-demand-$index"),
                            candidateId = ImportCandidateId("candidate-demand-$index"),
                            statusHistoryId = ImportStatusHistoryId("status-demand-$index"),
                            duplicateIds =
                                List(requiredDuplicateIds) { group ->
                                    ImportDuplicateIntakeIds(
                                        candidateId = ImportDuplicateCandidateId("duplicate-demand-$index-$group"),
                                        statusHistoryId = ImportStatusHistoryId("duplicate-status-demand-$index-$group"),
                                    )
                                },
                        )
                    }

                // The winning intake must commit, and the store's demand must equal the
                // transaction-internal match count.
                assertIs<ImportIntakeResult.Accepted>(result)
                assertEquals(
                    expectedMatches,
                    demands.last(),
                    "intake $index: the store's demand must equal the in-transaction selectDuplicateMatches count",
                )
            }
            assertEquals(listOf(0, 1, 2, 3), demands)
        } finally {
            driver.close()
        }
    }

    private fun assertEquivalent(
        driver: JdbcSqliteDriver,
        label: String,
        queryStatusToken: String? = statusToken,
        nullFlag: Long = 0L,
    ) {
        val database = LedgerDatabase(driver)
        val count =
            database.ledgerQueries
                .duplicateMatchCountForIntake(
                    ledgerId.value,
                    ImportRecordKind.ORDINARY_FLOW_SOURCE.storageValue,
                    ImportRecordKind.ORDINARY_FLOW_SOURCE.contractVersion.toLong(),
                    amountMinor,
                    currencyCode,
                    currencyPrecision,
                    occurredAt,
                    directionToken,
                    queryStatusToken,
                    nullFlag,
                ).executeAsOne()
        val matches =
            database.ledgerQueries
                .selectDuplicateMatches(
                    ledgerId.value,
                    "source-sentinel-new-not-yet-inserted",
                    ImportRecordKind.ORDINARY_FLOW_SOURCE.storageValue,
                    ImportRecordKind.ORDINARY_FLOW_SOURCE.contractVersion.toLong(),
                    amountMinor,
                    currencyCode,
                    currencyPrecision,
                    occurredAt,
                    directionToken,
                    queryStatusToken,
                    nullFlag,
                ).executeAsList()
        assertEquals(
            matches.size.toLong(),
            count,
            "$label: duplicateMatchCountForIntake must equal selectDuplicateMatches row count",
        )
    }

    private fun requestRow(
        driver: JdbcSqliteDriver,
        ledger: LedgerId,
        requestId: String,
    ) {
        driver.execute(
            null,
            "INSERT INTO import_request(ledger_id, request_id, operation) VALUES ('${ledger.value}', '$requestId', 'intake')",
            0,
        )
    }

    private fun insertSource(
        driver: JdbcSqliteDriver,
        sourceId: String,
        amount: Long,
        direction: String,
        status: String,
        fundingState: String,
        completeness: String,
        ledger: LedgerId = ledgerId,
    ) {
        // One owner request per row (UNIQUE (ledger_id, owner_request_id) and
        // UNIQUE (ledger_id, input_ref, record_ordinal) both need distinct values).
        val requestId = "req-$sourceId"
        requestRow(driver, ledger, requestId)
        val hash = ImportContentFingerprint().digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, facts(amount, direction, status))
        driver.execute(
            null,
            """
            INSERT INTO import_source_record(
              ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind,
              content_hash, contract_version, completeness, amount_minor, currency_code,
              currency_precision, occurred_at, direction_token, status_token, funding_state,
              funding_rule_id, funding_rule_version, candidate_generated_at
            ) VALUES (
              '${ledger.value}', '$sourceId', '$requestId', 'syn-ref-$sourceId', 0, 'ordinary_flow_source',
              '$hash', 1, '$completeness', $amount, 'CNY',
              2, '$occurredAt', '$direction', '$status', '$fundingState',
              'synthetic-rule', 1, 'legacy-intake-v1'
            )
            """.trimIndent(),
            0,
        )
    }

    private fun facts(
        amount: Long,
        direction: String,
        status: String,
    ) = ImportSourceFacts(
        amountMinor = amount,
        currencyCode = currencyCode,
        currencyPrecision = 2,
        occurredAt = occurredAt,
        directionToken = direction,
        statusToken = status,
        fundingState = ImportFundingState.SETTLED,
        fundingRuleId = "synthetic-rule",
        fundingRuleVersion = 1,
    )
}
