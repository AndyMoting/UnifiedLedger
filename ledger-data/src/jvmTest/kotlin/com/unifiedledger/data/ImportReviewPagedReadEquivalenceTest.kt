package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.ExecuteImportIntake
import com.unifiedledger.application.IMPORT_FUNDING_RULE_LEGACY_SETTLED
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportContentFingerprint
import com.unifiedledger.application.ImportDuplicateCandidateId
import com.unifiedledger.application.ImportDuplicateIntakeIds
import com.unifiedledger.application.ImportDuplicateStatus
import com.unifiedledger.application.ImportEvidenceId
import com.unifiedledger.application.ImportFundingState
import com.unifiedledger.application.ImportIntakeIdSource
import com.unifiedledger.application.ImportIntakeIds
import com.unifiedledger.application.ImportIntakeRequest
import com.unifiedledger.application.ImportIntakeResult
import com.unifiedledger.application.ImportRecordKind
import com.unifiedledger.application.ImportRequestId
import com.unifiedledger.application.ImportRequestIdentity
import com.unifiedledger.application.ImportReviewRow
import com.unifiedledger.application.ImportReviewRowsResult
import com.unifiedledger.application.ImportSourceFacts
import com.unifiedledger.application.ImportSourceId
import com.unifiedledger.application.ImportStatusHistoryId
import com.unifiedledger.application.QueryImportReviewRows
import com.unifiedledger.data.db.ImportReviewRowsForLedger
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.LedgerId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P7-04 OOM fix (design spec 2026-09-21 v0.2, D-168): the candidate-boundary keyset-paged list
 * read (`importReviewRowsForLedgerPage`) must fold to exactly the whole-ledger read it replaces.
 * The reference implementation below reconstructs the pre-fix read shape from the still-present
 * whole-ledger query `importReviewRowsForLedger` and the same blocking-first fold the adapter
 * uses (the fold itself is covered by ImportReviewTargetedReadEquivalenceTest; this suite tests
 * the paging boundary, not the fold). Everything runs on the real spine write path
 * (ExecuteImportIntake) over IN_MEMORY JDBC; every id, amount and instant is anonymous synthetic.
 *
 * The stable acceptance IDs (spec section 7) map to the tests as:
 *  - P704OOM-T-BATCH-01      batchVsWholeLedgerFullEquivalence (small pageSize forces multi-batch)
 *  - P704OOM-T-BOUNDARY-01   pageBoundaryCandidateCompleteness
 *  - P704OOM-T-PARAM-01      pageSizeEndpoints
 *  - P704OOM-T-TAIL-01       emptyLibraryAndFullTailTermination
 *  - P704OOM-T-G6-01         readFailurePropagatesWithoutPartialList
 *  - P704OOM-T-PAGESIZE-01   degeneratePageSizeRejectedBeforeAnyQuery
 *  - P704OOM-T-COLLATION-01  supplementaryPlaneIdProducesDuplicateNotSilentLoss
 *  - P704OOM-T-EMPTYID-01    emptyStringCandidateIdFailsLoud
 *  - P704OOM-T-NESTING-01    nestingInsideATransactionFailsLoudAndMapsToUnavailable
 */
class ImportReviewPagedReadEquivalenceTest {
    private val ledgerId = LedgerId("ledger-p704oom")

    /**
     * Allocates ids deterministically from the caller's candidate-id list: the k-th intake
     * takes the k-th candidate id and mints exactly the `requiredDuplicateIds` the store asks
     * for. This lets a test control candidate ids (hence the SQLite BINARY read order) without
     * pre-computing the spine's duplicate demand.
     */
    private class GeneratedIntakeIdSource(
        private val candidateIds: List<String>,
    ) : ImportIntakeIdSource {
        val calls = AtomicInteger(0)

        override fun next(requiredDuplicateIds: Int): ImportIntakeIds {
            val index = calls.getAndIncrement()
            require(index < candidateIds.size) { "intake id batch exhausted" }
            val candidateId = candidateIds[index]
            return ImportIntakeIds(
                sourceId = ImportSourceId("source-$candidateId"),
                evidenceId = ImportEvidenceId("evidence-$candidateId"),
                candidateId = ImportCandidateId(candidateId),
                statusHistoryId = ImportStatusHistoryId("status-$candidateId"),
                duplicateIds =
                    (0 until requiredDuplicateIds).map { duplicate ->
                        ImportDuplicateIntakeIds(
                            ImportDuplicateCandidateId("duplicate-$candidateId-$duplicate"),
                            ImportStatusHistoryId("duplicate-status-$candidateId-$duplicate"),
                        )
                    },
            )
        }
    }

    private fun settledRow(
        requestId: String,
        inputRef: String,
        amountMinor: Long,
    ) = ImportIntakeRequest(
        identity = ImportRequestIdentity(ledgerId, ImportRequestId(requestId)),
        inputRef = inputRef,
        recordOrdinal = 0,
        recordKind = ImportRecordKind.ORDINARY_FLOW_SOURCE,
        facts =
            ImportSourceFacts(
                amountMinor,
                "CNY",
                2,
                "2026-09-01T12:30:00+08:00",
                "out",
                "settled",
                ImportFundingState.SETTLED,
                IMPORT_FUNDING_RULE_LEGACY_SETTLED,
                1,
            ),
        completeness = ImportCompleteness.VALID_COMPLETE,
        candidateGeneratedAt = "2026-09-22T08:00:00Z",
    )

    /**
     * Intakes one settled record per descriptor `(candidateId, amountMinor)`. Records sharing an
     * amount share their business tuple, so the k-th record of a tuple group mints k-1 duplicate
     * candidates (the spine's exact-tuple match) — this yields the mixed multi-candidate /
     * multi-duplicate / no-duplicate library the paging tests need.
     */
    private fun intake(
        database: LedgerDatabase,
        driver: JdbcSqliteDriver,
        descriptors: List<Pair<String, Long>>,
    ) {
        val store = SqlDelightImportSpineStore(database, driver)
        val ids = GeneratedIntakeIdSource(descriptors.map { it.first })
        val execute = ExecuteImportIntake(store, ids, ImportContentFingerprint())
        descriptors.forEachIndexed { index, (candidateId, amountMinor) ->
            assertIs<ImportIntakeResult.Accepted>(
                execute.execute(settledRow("request-$candidateId", "batch-p704-$index", amountMinor)),
                "intake of $candidateId must be accepted",
            )
        }
    }

    /**
     * The whole-ledger reference: `importReviewRowsForLedger` folded per candidate with the same
     * blocking-first shape the adapter uses. This is the pre-fix read semantics the paged read
     * must reproduce row for row.
     */
    private fun wholeLedgerReference(database: LedgerDatabase): List<ImportReviewRow> =
        database.ledgerQueries
            .importReviewRowsForLedger(ledgerId.value)
            .executeAsList()
            .groupBy { it.candidate_id }
            .map { (_, grouped) -> referenceRow(grouped.first(), grouped) }

    private fun referenceRow(
        first: ImportReviewRowsForLedger,
        grouped: List<ImportReviewRowsForLedger>,
    ): ImportReviewRow {
        val duplicateTokens =
            grouped
                .filter { it.duplicate_candidate_id != null }
                .mapNotNull { it.duplicate_latest_status }
        return ImportReviewRow(
            candidateId = ImportCandidateId(first.candidate_id),
            candidateKind = first.candidate_kind,
            sourceInputRef = first.input_ref,
            amountMinor = first.amount_minor,
            currencyCode = first.currency_code,
            currencyPrecision = first.currency_precision?.toInt(),
            occurredAt = first.occurred_at,
            directionToken = first.direction_token,
            statusToken = first.status_token,
            fundingState = ImportFundingState.valueOf(first.funding_state),
            completeness =
                when (first.completeness) {
                    "valid_complete" -> ImportCompleteness.VALID_COMPLETE
                    "valid_incomplete" -> ImportCompleteness.VALID_INCOMPLETE
                    else -> error("unknown completeness token: ${first.completeness}")
                },
            contentHash = first.content_hash,
            candidateStatus = requireNotNull(first.candidate_status),
            requiresConfirmation = first.requires_confirmation,
            confidence = first.confidence,
            duplicateStatus =
                duplicateTokens
                    .map { ImportDuplicateStatus.valueOf(it) }
                    .minByOrNull { foldPriority(it) },
            paymentProfileVariant = first.payment_profile_variant,
            paymentProfileAssetLegKindToken = first.payment_profile_asset_leg_kind_token,
            paymentProfileCreditLegKindToken = first.payment_profile_credit_leg_kind_token,
        )
    }

    private fun foldPriority(status: ImportDuplicateStatus): Int =
        when (status) {
            ImportDuplicateStatus.CONFIRMED_DUPLICATE -> 0
            ImportDuplicateStatus.DEFERRED -> 1
            ImportDuplicateStatus.CONFIRMED_DISTINCT -> 2
            ImportDuplicateStatus.DISMISSED_LOOKALIKE -> 3
            ImportDuplicateStatus.REJECTED -> 4
        }

    /** Mixed library: a 3-record tuple group (two duplicates on the third), a 2-record group,
     * and one no-duplicate candidate — multi-candidate, multi-duplicate and no-duplicate in one. */
    private fun mixedDescriptors(prefix: String): List<Pair<String, Long>> =
        listOf(
            "$prefix-00" to 1_000L,
            "$prefix-01" to 1_000L,
            "$prefix-02" to 1_000L,
            "$prefix-03" to 2_000L,
            "$prefix-04" to 2_000L,
            "$prefix-05" to 3_000L,
        )

    private fun withMixedLibrary(block: (LedgerDatabase) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            intake(database, driver, mixedDescriptors("p704"))
            block(database)
        } finally {
            driver.close()
        }
    }

    @Test
    fun batchVsWholeLedgerFullEquivalence() {
        // P704OOM-T-BATCH-01: pageSize=2 forces the multi-batch path over 6 candidates whose
        // duplicate counts differ; the paged fold must equal the whole-ledger fold row for row
        // (candidate fields, folded duplicate verdict, payment profile columns — full equality).
        withMixedLibrary { database ->
            val reference = wholeLedgerReference(database)
            assertEquals(6, reference.size, "fixture must yield one folded row per candidate")
            // The 3-record tuple group's last candidate folds two duplicates to DEFERRED; the
            // lone candidate folds to null. The paged read must reproduce both.
            assertEquals(ImportDuplicateStatus.DEFERRED, reference.first { it.candidateId.value == "p704-02" }.duplicateStatus)
            assertEquals(null, reference.first { it.candidateId.value == "p704-05" }.duplicateStatus)
            val paged = SqlDelightImportReviewReadAdapter(database, pageSize = 2).loadImportReviewRows(ledgerId)
            assertEquals(reference, paged)
        }
    }

    @Test
    fun pageBoundaryCandidateCompleteness() {
        // P704OOM-T-BOUNDARY-01: pageSize+1 candidates (a boundary candidate beyond the first
        // page) and an exact multiple of pageSize. The boundary candidate's duplicate rows must
        // all land in one batch, so the fold equals the whole-ledger reference in both shapes.
        val pageSize = 3L
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            intake(
                database,
                driver,
                listOf(
                    "boundary-00" to 1_000L,
                    "boundary-01" to 1_000L,
                    "boundary-02" to 1_000L,
                    "boundary-03" to 1_000L,
                ),
            )
            val reference = wholeLedgerReference(database)
            assertEquals(4, reference.size)
            // The last candidate carries three duplicates (four-record tuple group); its fold
            // must survive the page boundary at pageSize=3.
            assertEquals(ImportDuplicateStatus.DEFERRED, reference.last().duplicateStatus)
            assertEquals(
                reference,
                SqlDelightImportReviewReadAdapter(database, pageSize = pageSize).loadImportReviewRows(ledgerId),
            )
            // Exactly two full pages: the tail is exactly full and must still terminate.
            assertEquals(
                reference,
                SqlDelightImportReviewReadAdapter(database, pageSize = 2).loadImportReviewRows(ledgerId),
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun pageSizeEndpoints() {
        // P704OOM-T-PARAM-01: pageSize=1 (one candidate per batch) and pageSize=whole library
        // (a single batch, degenerating to the pre-fix whole-ledger read) both equal the reference.
        withMixedLibrary { database ->
            val reference = wholeLedgerReference(database)
            assertEquals(reference, SqlDelightImportReviewReadAdapter(database, pageSize = 1).loadImportReviewRows(ledgerId))
            assertEquals(reference, SqlDelightImportReviewReadAdapter(database, pageSize = reference.size.toLong()).loadImportReviewRows(ledgerId))
            assertEquals(reference, SqlDelightImportReviewReadAdapter(database, pageSize = 1_000).loadImportReviewRows(ledgerId))
        }
    }

    @Test
    fun emptyLibraryAndFullTailTermination() {
        // P704OOM-T-TAIL-01: an empty library reads as an empty list; a candidate count that is
        // an exact multiple of pageSize terminates on the full tail batch (the invariant is
        // batch.size < pageSize, so a full tail simply runs one more empty batch and stops).
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            assertEquals(emptyList(), SqlDelightImportReviewReadAdapter(database, pageSize = 2).loadImportReviewRows(ledgerId))
            intake(
                database,
                driver,
                listOf("tail-00" to 1_000L, "tail-01" to 1_000L, "tail-02" to 2_000L, "tail-03" to 2_000L),
            )
            val reference = wholeLedgerReference(database)
            assertEquals(4, reference.size)
            assertEquals(reference, SqlDelightImportReviewReadAdapter(database, pageSize = 2).loadImportReviewRows(ledgerId))
        } finally {
            driver.close()
        }
    }

    @Test
    fun readFailurePropagatesWithoutPartialList() {
        // P704OOM-T-G6-01: a failure while folding a LATER batch must fail the whole read (the
        // transaction rolls back and nothing is returned) rather than yielding a truncated list.
        // The fixture intakes one good candidate, then raw-inserts a second candidate whose
        // status history is absent (the schema guards UPDATE/DELETE but allows this INSERT),
        // so toImportReviewRow's requireNotNull(candidate_status) fires on the second batch.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            intake(database, driver, listOf("g6-00" to 1_000L))
            insertCandidateWithoutStatusHistory(driver, "g6-broken")
            val adapter = SqlDelightImportReviewReadAdapter(database, pageSize = 1)
            assertFailsWith<IllegalArgumentException> { adapter.loadImportReviewRows(ledgerId) }
            // The use-case boundary maps the same failure to the typed Unavailable (G6), never to
            // a partial or empty list.
            assertEquals(ImportReviewRowsResult.Unavailable, QueryImportReviewRows(adapter).query(ledgerId))
        } finally {
            driver.close()
        }
    }

    /** Raw-inserts a well-formed source + candidate with NO status-history row. */
    private fun insertCandidateWithoutStatusHistory(
        driver: JdbcSqliteDriver,
        candidateId: String,
    ) {
        driver.execute(
            null,
            "INSERT INTO import_request(ledger_id, request_id, operation) VALUES ('${ledgerId.value}', 'request-$candidateId', 'intake')",
            0,
        )
        driver.execute(
            null,
            """
            INSERT INTO import_source_record(
              ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind,
              content_hash, contract_version, completeness, amount_minor, currency_code,
              currency_precision, occurred_at, direction_token, status_token, funding_state,
              funding_rule_id, funding_rule_version, candidate_generated_at
            ) VALUES (
              '${ledgerId.value}', 'source-$candidateId', 'request-$candidateId', 'batch-$candidateId', 0,
              'ordinary_flow_source', 'sha256:anonymous', 1, 'valid_complete', 700, 'CNY', 2,
              '2026-09-01T12:30:00+08:00', 'out', 'settled', 'SETTLED', 'legacy-settled-v1', 1,
              '2026-09-22T08:00:00Z'
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            "INSERT INTO import_candidate(ledger_id, candidate_id, source_id, candidate_kind, confidence, rule, rule_version) " +
                "VALUES ('${ledgerId.value}', '$candidateId', 'source-$candidateId', 'ordinary_flow', 'high', 'ordinary_flow_source', 1)",
            0,
        )
    }

    @Test
    fun degeneratePageSizeRejectedBeforeAnyQuery() {
        // P704OOM-T-PAGESIZE-01: pageSize=0 would send maxOf into NoSuchElementException on an
        // empty batch and a negative value would read as SQLite LIMIT unlimited; both are
        // rejected by require(pageSize >= 1) before any query runs.
        withMixedLibrary { database ->
            assertFailsWith<IllegalArgumentException> {
                SqlDelightImportReviewReadAdapter(database, pageSize = 0).loadImportReviewRows(ledgerId)
            }
            assertFailsWith<IllegalArgumentException> {
                SqlDelightImportReviewReadAdapter(database, pageSize = -1).loadImportReviewRows(ledgerId)
            }
        }
    }

    @Test
    fun supplementaryPlaneIdProducesDuplicateNotSilentLoss() {
        // P704OOM-T-COLLATION-01: the keyset advance uses Kotlin's UTF-16 code-unit order while
        // SQLite ORDER BY uses BINARY (UTF-8 byte) order. For supplementary-plane ids the two
        // disagree: U+FFFD sorts before U+1F600 in BINARY, but U+1F600 sorts before U+FFFD in
        // UTF-16. With pageSize=2 the batch's Kotlin maxOf picks U+FFFD, so the next batch
        // re-reads U+1F600 — the declared failure mode is a DUPLICATE candidate, not silent loss.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            intake(database, driver, listOf("\uFFFD" to 1_000L, "\uD83D\uDE00" to 2_000L))
            val reference = wholeLedgerReference(database)
            assertEquals(2, reference.size, "each id is unique, so the reference has two rows")
            val paged = SqlDelightImportReviewReadAdapter(database, pageSize = 2).loadImportReviewRows(ledgerId)
            assertEquals(3, paged.size, "the supplementary-plane divergence must surface as a duplicate candidate")
            assertEquals(
                2,
                paged.count { it.candidateId == ImportCandidateId("\uD83D\uDE00") },
                "U+1F600 is read twice; nothing is silently lost",
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun emptyStringCandidateIdFailsLoud() {
        // P704OOM-T-EMPTYID-01: the keyset cursor starts at "" and advances with
        // `candidate_id > after`, so a candidate whose id is exactly "" would be permanently
        // skipped. The schema allows "" (NOT NULL only), so the adapter detects it and fails
        // loud rather than silently omitting the candidate (spec 2.2 point 4 / 6 item 4a).
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            intake(database, driver, listOf("emptyid-00" to 1_000L, "" to 2_000L))
            val adapter = SqlDelightImportReviewReadAdapter(database, pageSize = 2)
            assertFailsWith<IllegalStateException> { adapter.loadImportReviewRows(ledgerId) }
        } finally {
            driver.close()
        }
    }

    @Test
    fun nestingInsideATransactionFailsLoudAndMapsToUnavailable() {
        // P704OOM-T-NESTING-01: loadImportReviewRows is a non-nesting contract. Under
        // noEnclosing = true, calling it inside an existing transaction throws
        // IllegalStateException("Already in a transaction") rather than silently nesting; the
        // use-case boundary maps that to Unavailable. Both facts are pinned here.
        withMixedLibrary { database ->
            val adapter = SqlDelightImportReviewReadAdapter(database, pageSize = 2)
            assertFailsWith<IllegalStateException> {
                database.transactionWithResult { adapter.loadImportReviewRows(ledgerId) }
            }
            val mapped = database.transactionWithResult { QueryImportReviewRows(adapter).query(ledgerId) }
            assertTrue(mapped is ImportReviewRowsResult.Unavailable)
        }
    }
}
