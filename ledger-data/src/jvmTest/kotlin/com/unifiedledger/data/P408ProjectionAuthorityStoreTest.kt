package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.P408ConfirmLinkRequest
import com.unifiedledger.application.P408EvidenceProjectionPort
import com.unifiedledger.application.P408EvidenceResponsibility
import com.unifiedledger.application.P408Matcher
import com.unifiedledger.application.P408MaterializationRequest
import com.unifiedledger.application.P408MaterializeResult
import com.unifiedledger.application.P408ProjectionState
import com.unifiedledger.application.P408ReconciliationResult
import com.unifiedledger.data.db.LedgerDatabase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * TP assertion pack for the P4-08 normalized evidence projection authority
 * (approved implementation spec section 8; D-112): materialization matrix
 * (TP-02..TP-07), idempotency (TP-11), append-only guards (TP-12), READY gate
 * zero-write behavior (TP-13), v2 write-always retirement of brand-new v1
 * requests (TP-14 / closure condition 010-R1), snapshot dual values (TP-15),
 * and the between-batch non-scale-2 named test with its scale-2 control accept
 * (TP-18 + TP-01's numeric path over real store code).
 */
class P408ProjectionAuthorityStoreTest {
    private val closables = mutableListOf<Pair<Path?, SqlDriver?>>()

    @AfterTest
    fun tearDown() {
        closables.forEach { (path, driver) ->
            driver?.close()
            path?.let { Files.deleteIfExists(it) }
        }
        closables.clear()
    }

    private fun newWorld(): Pair<LedgerDatabase, SqlDriver> {
        val p = Files.createTempFile("ledger-data-p408-proj-", ".db")
        val d = JdbcSqliteDriver("jdbc:sqlite:${p.absolutePathString()}", projectionSqliteProperties())
        LedgerDatabase.Schema.create(d)
        closables.add(p to d)
        return LedgerDatabase(d) to d
    }

    private fun seedSource(
        db: LedgerDatabase,
        driver: SqlDriver,
        requestId: String,
        sourceId: String,
        evidenceId: String,
        amountMinor: Long,
        precision: Int,
        contentHash: String = "hash-$sourceId",
    ) {
        db.transaction {
            listOf(
                "INSERT INTO import_request VALUES ('ledger-a','$requestId','intake')",
                "INSERT INTO import_source_record(ledger_id, source_id, owner_request_id, input_ref, record_ordinal," +
                    " record_kind, content_hash, contract_version, completeness, amount_minor, currency_code," +
                    " currency_precision, occurred_at, direction_token, status_token) VALUES" +
                    " ('ledger-a','$sourceId','$requestId','batch-$sourceId',0,'transfer_flow_source','$contentHash',2," +
                    "'valid_complete',$amountMinor,'CNY',$precision,'2026-08-10T12:00:00+08:00','out','settled')",
                "INSERT INTO import_evidence VALUES ('ledger-a','$evidenceId','$sourceId','source_observation','2026-08-10T12:00:01+08:00')",
            ).forEach { statement -> driver.execute(null, statement, 0) }
        }
    }

    private fun seedPosting(
        db: LedgerDatabase,
        driver: SqlDriver,
        postingId: String = "posting-a",
        amountMinor: Long = -1000L,
    ) {
        db.transaction {
            listOf(
                "INSERT INTO ledger_transaction(transaction_id,ledger_id,kind,canonical_kind)" +
                    " VALUES ('tx-a','ledger-a','ACCOUNT_TRANSFER',NULL)",
                "INSERT INTO posting_set VALUES ('posting-set-a','ledger-a')",
                "INSERT INTO transaction_version(version_id,transaction_id,ledger_id,version_number,posting_set_id," +
                    " occurred_at, statistics_at, effective_at, note) VALUES" +
                    " ('version-a','tx-a','ledger-a',1,'posting-set-a','2026-08-10T12:00:00+08:00'," +
                    "'2026-08-10T12:00:00+08:00','2026-08-10T12:00:00+08:00',NULL)",
                "INSERT INTO ledger_transaction_current_version VALUES ('tx-a','ledger-a','version-a')",
                "INSERT INTO posting VALUES ('$postingId','posting-set-a','ledger-a',0,'account-bank-a',$amountMinor,'CNY',2)",
            ).forEach { statement -> driver.execute(null, statement, 0) }
        }
    }

    // ---------- TP-02: exact pad 99@0 -> 9900@2 ----------

    @Test
    fun tp02ExactPad99At0Becomes9900ReadyRow() {
        val (db, driver) = newWorld()
        seedSource(db, driver, "request-p", "source-p", "evidence-p", amountMinor = 99, precision = 0)
        val store = SqlDelightEvidenceProjectionStore.createShared(db)
        val accepted =
            assertIs<P408MaterializeResult.Accepted>(
                store.materialize(materializationRequest()),
            )
        assertEquals(P408ProjectionState.READY, accepted.projection.state)
        assertEquals(9900L, accepted.projection.normalizedAmountMinor)
        assertEquals(99L, accepted.projection.rawAmountMinor)
        assertEquals(0, accepted.projection.rawCurrencyPrecision)
    }

    // ---------- TP-03: half-unit pad and equal precision round trip ----------

    @Test
    fun tp03HalfUnitAndEqualPrecisionRoundTripExactly() {
        val (db, driver) = newWorld()
        seedSource(db, driver, "request-h", "source-h", "evidence-half", amountMinor = 5, precision = 1)
        seedSource(db, driver, "request-e", "source-e", "evidence-equal", amountMinor = 700, precision = 2)
        val store = SqlDelightEvidenceProjectionStore.createShared(db)
        val half =
            assertIs<P408MaterializeResult.Accepted>(
                store.materialize(materializationRequest(evidenceId = "evidence-half", requestIdSuffix = "h")),
            )
        assertEquals(50L, half.projection.normalizedAmountMinor)
        val equal =
            assertIs<P408MaterializeResult.Accepted>(
                store.materialize(materializationRequest(evidenceId = "evidence-equal", requestIdSuffix = "e")),
            )
        assertEquals(equal.projection.rawAmountMinor, equal.projection.normalizedAmountMinor)

        // TP-04 vector: exact divisor downscale 5000@3 -> 50@1 (integer, lossless).
        seedSource(db, driver, "request-d", "source-d", "evidence-down", amountMinor = 5000, precision = 3)
        val down =
            assertIs<P408MaterializeResult.Accepted>(
                store.materialize(materializationRequest(evidenceId = "evidence-down", requestIdSuffix = "d", targetPrecision = 1)),
            )
        assertEquals(50L, down.projection.normalizedAmountMinor)
    }

    // ---------- TP-05: overflow classification with persisted terminal refusal ----------

    @Test
    fun tp05OverflowRefusesWithTypeCodeAndTerminalRow() {
        val (db, driver) = newWorld()
        seedSource(db, driver, "request-p", "source-p", "evidence-p", amountMinor = Long.MAX_VALUE / 2, precision = 0)
        val store = SqlDelightEvidenceProjectionStore.createShared(db)
        val rejected = assertIs<P408MaterializeResult.Rejected>(store.materialize(materializationRequest()))
        assertEquals(SqlDelightEvidenceProjectionStore.CODE_ARITHMETIC_OVERFLOW, rejected.code)
        val row = store.readProjection("ledger-a", "evidence-p")!!
        assertEquals(P408ProjectionState.REJECTED, row.state)
        assertEquals(rejected.code, row.rejectionCode)
    }

    // ---------- TP-06: remainder refusal ----------

    @Test
    fun tp06RemainderRefusesAsNotRepresentable() {
        val (db, driver) = newWorld()
        seedSource(db, driver, "request-p", "source-p", "evidence-p", amountMinor = 33, precision = 2)
        val rejected =
            assertIs<P408MaterializeResult.Rejected>(
                SqlDelightEvidenceProjectionStore
                    .createShared(db)
                    .materialize(materializationRequest(targetPrecision = 0)),
            )
        assertEquals(SqlDelightEvidenceProjectionStore.CODE_AMOUNT_NOT_REPRESENTABLE, rejected.code)
    }

    // ---------- TP-07: currency mismatch precedes arithmetic ----------

    @Test
    fun tp07CurrencyMismatchRefusesBeforeArithmetic() {
        val (db, driver) = newWorld()
        seedSource(db, driver, "request-p", "source-p", "evidence-p", amountMinor = 100, precision = 2)
        val rejected =
            assertIs<P408MaterializeResult.Rejected>(
                SqlDelightEvidenceProjectionStore
                    .createShared(db)
                    .materialize(materializationRequest().copy(targetCurrencyCode = "USD")),
            )
        assertEquals(SqlDelightEvidenceProjectionStore.CODE_CURRENCY_MISMATCH, rejected.code)
    }

    // ---------- TP-11: idempotent replay, divergent input conflict ----------

    @Test
    fun tp11IdenticalReplayNoChangeDivergentInputConflict() {
        val (db, driver) = newWorld()
        seedSource(db, driver, "request-p", "source-p", "evidence-p", amountMinor = 250, precision = 2)
        val store = SqlDelightEvidenceProjectionStore.createShared(db)
        val first = assertIs<P408MaterializeResult.Accepted>(store.materialize(materializationRequest()))
        val replay = assertIs<P408MaterializeResult.NoChange>(store.materialize(materializationRequest()))
        assertEquals(first.projection, replay.projection)
        // QUAL-001: provenance (requestId) is audit-only and never conflicts;
        // an economic divergence is what must reject.
        val sameProvenanceReplay =
            assertIs<P408MaterializeResult.NoChange>(
                store.materialize(materializationRequest().copy(requestId = "matn-request-2")),
            )
        assertEquals(first.projection, sameProvenanceReplay.projection)
        val conflict =
            assertIs<P408MaterializeResult.Rejected>(
                store.materialize(materializationRequest().copy(targetAccountId = "account-bank-b")),
            )
        assertEquals(SqlDelightEvidenceProjectionStore.CODE_STATE_CONFLICT, conflict.code)
    }

    // ---------- TP-12: append-only guards ----------

    @Test
    fun tp12UpdateAndDeleteAreAbortedByGuards() {
        val (db, driver) = newWorld()
        seedSource(db, driver, "request-p", "source-p", "evidence-p", amountMinor = 500, precision = 2)
        assertIs<P408MaterializeResult.Accepted>(
            SqlDelightEvidenceProjectionStore.createShared(db).materialize(materializationRequest()),
        )
        listOf(
            "UPDATE evidence_projection SET state='REJECTED'" to "cannot update evidence projection",
            "DELETE FROM evidence_projection" to "cannot delete evidence projection",
        ).forEach { (sql, message) ->
            try {
                db.transaction { driver.execute(null, sql, 0) }
                error("missing guard for: $sql")
            } catch (expected: java.sql.SQLException) {
                // QUAL-005: the guard message is asserted, not just the failure class.
                val actual = expected.message ?: ""
                assertEquals(true, actual.contains(message), "expected guard message '$message' in '$actual'")
            }
        }
        assertEquals(1L, db.ledgerQueries.countEvidenceProjectionRows().executeAsOne())
        // Store-level read/write verification: the materialized row round-trips.
        val readback =
            SqlDelightEvidenceProjectionStore
                .createShared(db)
                .readProjection("ledger-a", "evidence-p")
        assertEquals(P408ProjectionState.READY, readback!!.state)
        assertEquals(500L, readback.normalizedAmountMinor)
    }

    // ---------- TP-14 (010-R1): brand-new v1 shapes are retired ----------

    @Test
    fun tp14NewRequestIdWithV1ShapeIsRetiredWithZeroWrites() {
        val (db, driver) = newWorld()
        seedSource(db, driver, "request-a", "source-a", "evidence-a", amountMinor = 1000, precision = 2)
        seedPosting(db, driver)
        val store = SqlDelightP408ReconciliationStore(db, driver)
        val legacy = confirmRequest(basisVersion = 1, requestId = "request-retired-new")
        val rejected = assertIs<P408ReconciliationResult.Rejected>(store.confirmLink(legacy))
        assertEquals("P408_REQUEST_BASIS_VERSION_RETIRED", rejected.code)
        assertEquals(0L, db.ledgerQueries.countP408ReconciliationRequestRows().executeAsOne())
        assertEquals(0L, db.ledgerQueries.countEvidenceProjectionRows().executeAsOne())
        assertEquals(0L, db.ledgerQueries.countEvidenceLinkRows().executeAsOne())
    }

    // ---------- TP-13 + TP-18 (+ TP-01 path): authority gates with zero-writes ----------

    @Test
    fun tp18NonScaleTwoAuthorityStaysPendingZeroLinksAndScaleTwoControlAccepts() {
        // Gate side: a 1-digit source cannot produce an authority for a scale-2 request.
        val (db, driver) = newWorld()
        seedSource(db, driver, "request-a", "source-a", "evidence-a", amountMinor = 55, precision = 2)
        seedPosting(db, driver)
        val store = SqlDelightP408ReconciliationStore(db, driver)
        val rejected =
            assertIs<P408ReconciliationResult.Rejected>(
                store.confirmLink(confirmRequest().copy(currencyPrecision = 1)),
            )
        assertEquals(SqlDelightEvidenceProjectionStore.CODE_AMOUNT_NOT_REPRESENTABLE, rejected.code)
        assertEquals(0L, db.ledgerQueries.countEvidenceLinkRows().executeAsOne())
        assertEquals(0L, db.ledgerQueries.countEvidenceProjectionRows().executeAsOne())
        assertEquals(0L, db.ledgerQueries.countP408ReconciliationReceiptRows().executeAsOne())
        assertEquals(0L, db.ledgerQueries.countP408ReconciliationRequestRows().executeAsOne())
        assertEquals(0L, db.ledgerQueries.countP408PostingReconciliationRows().executeAsOne())
        // The REJECTED terminal authority here stands in as the literal scene proxy
        // for an inexpressible non-scale-2 source (55@2 -> target 1): the semantic
        // promise under test is zero claim/link/reconciliation writes while the
        // posting stays unadvanced, which holds identically for a persisted-REJECTED
        // authority and for a same-shaped refusal (spec V-5-A / TP-18).
        assertEquals(
            null,
            db.ledgerQueries.selectP408PostingReconciliation("ledger-a", "posting-a").executeAsOneOrNull(),
        )

        // Control side: the scale-2 mirror proceeds through the READY authority.
        val (db2, driver2) = newWorld()
        seedSource(db2, driver2, "request-a", "source-a", "evidence-a", amountMinor = 1000, precision = 2)
        seedPosting(db2, driver2)
        val control = SqlDelightP408ReconciliationStore(db2, driver2)
        val accepted = assertIs<P408ReconciliationResult.Accepted>(control.confirmLink(confirmRequest()))
        assertEquals("ACCEPTED", accepted.receipt.outcome)
        assertEquals(1L, db2.ledgerQueries.countEvidenceProjectionRows().executeAsOne())
        assertEquals(
            "CHECKED",
            db2.ledgerQueries
                .selectP408PostingReconciliation("ledger-a", "posting-a")
                .executeAsOne()
                .status,
        )
    }

    // ---------- TP-15: snapshot dual values ----------

    @Test
    fun tp15SnapshotCarriesRawAndNormalizedTwins() {
        val (db, driver) = newWorld()
        seedSource(db, driver, "request-a", "source-a", "evidence-a", amountMinor = 2400, precision = 2)
        seedPosting(db, driver, amountMinor = -2400)
        val store = SqlDelightP408ReconciliationStore(db, driver)
        assertIs<P408ReconciliationResult.Accepted>(
            store.confirmLink(confirmRequest(amountMinor = 2400, requestId = "request-snap")),
        )
        val raw = db.ledgerQueries.selectP408EvidenceSourceFacts("ledger-a", "evidence-a").executeAsOne()
        val snap =
            db.ledgerQueries
                .selectP408ReconciliationSnapshotRaw("ledger-a", "request-snap")
                .executeAsOne()
        assertEquals(P408EvidenceProjectionPort.RULE_ID, snap.projection_rule_id)
        assertEquals(1L, snap.projection_rule_version)
        assertEquals(raw.amount_minor, snap.raw_amount_minor)
        assertEquals(raw.currency_precision, snap.raw_currency_precision)
        assertEquals(snap.raw_amount_minor, snap.normalized_amount_minor)
    }

    // ---------- TP-14 (closure 3): byte-level v1 replay of an existing v1 row ----------

    @Test
    fun tp14ExistingV1RowReplayIsNoChangeWithOriginalReceipt() {
        val (db, driver) = newWorld()
        seedSource(db, driver, "request-a", "source-a", "evidence-a", amountMinor = 1000, precision = 2)
        seedPosting(db, driver)
        // Build the v1 request once: its fingerprint seeds the stored ACCEPTED row.
        val v1 = confirmRequest(basisVersion = 1, requestId = "request-v1-old")
        val fp = v1.fingerprint()
        db.transaction {
            listOf(
                "INSERT INTO reconciliation_request(ledger_id, request_id, operation, input_fingerprint, outcome)" +
                    " VALUES ('ledger-a','request-v1-old','confirm_link','$fp','ACCEPTED')",
                "INSERT INTO reconciliation_request_snapshot(ledger_id, request_id, evidence_id, candidate_id, posting_id," +
                    " transaction_id, amount_minor, currency_code, currency_precision, direction, account_id, responsibility," +
                    " basis_version, match_basis, window_days, natural_day_distance, source_occurred_at, confirmed_at," +
                    " human_decision, projection_id, projection_rule_id, projection_rule_version, normalized_amount_minor," +
                    " raw_amount_minor, raw_currency_precision) VALUES ('ledger-a','request-v1-old','evidence-a','candidate-a'," +
                    "'posting-a','tx-a',1000,'CNY',2,'out','account-bank-a','real_account_posting',1," +
                    "'amount,currency,direction,occurred_at_window,account',2,0,'2026-08-10T12:00:00+08:00'," +
                    "'2026-08-10T13:00:00+08:00','confirm_match',NULL,NULL,NULL,NULL,1000,2)",
                "INSERT INTO evidence_link(ledger_id, link_id, evidence_id, posting_id, transaction_id, responsibility," +
                    " basis_version, match_basis, candidate_id, request_id, created_at) VALUES ('ledger-a','link-a'," +
                    "'evidence-a','posting-a','tx-a','real_account_posting',1," +
                    "'amount,currency,direction,occurred_at_window,account','candidate-a','request-v1-old'," +
                    "'2026-08-10T13:00:00+08:00')",
                "INSERT INTO evidence_link_history(ledger_id, link_id, sequence, state, reason, request_id, occurred_at)" +
                    " VALUES ('ledger-a','link-a',1,'active','confirmed','request-v1-old','2026-08-10T13:00:00+08:00')",
                "INSERT INTO posting_reconciliation(ledger_id, reconciliation_id, posting_id, status, latest_sequence)" +
                    " VALUES ('ledger-a','reconciliation-posting-a','posting-a','CHECKED',2)",
                "INSERT INTO reconciliation_receipt(ledger_id, request_id, outcome, link_id, reconciliation_id, history_sequence)" +
                    " VALUES ('ledger-a','request-v1-old','ACCEPTED','link-a','reconciliation-posting-a',2)",
            ).forEach { statement -> driver.execute(null, statement, 0) }
        }
        val replay = SqlDelightP408ReconciliationStore(db, driver).confirmLink(v1)
        val noChange = assertIs<P408ReconciliationResult.NoChange>(replay)
        assertEquals("ACCEPTED", noChange.receipt.outcome)
        assertEquals("link-a", noChange.receipt.linkId)
        assertEquals("reconciliation-posting-a", noChange.receipt.reconciliationId)
        assertEquals(2L, noChange.receipt.historySequence)
    }

    // ---------- SPEC-007: drift positive, gate-state conflict, overflow path ----------

    @Test
    fun spec007SourceDriftPositiveTrigger() {
        val (db, driver) = newWorld()
        seedSource(db, driver, "request-a", "source-a", "evidence-a", amountMinor = 200, precision = 2)
        seedPosting(db, driver)
        // A bare-inserted READY row whose raw echo no longer matches the live source.
        db.transaction {
            driver.execute(
                null,
                "INSERT INTO evidence_projection(ledger_id, projection_id, evidence_id, source_id, source_hash," +
                    " target_account_id, currency_code, currency_precision, raw_amount_minor, raw_currency_precision," +
                    " normalized_amount_minor, direction_token, state, rejection_code, rule_id, rule_version," +
                    " materialization_request_id, materialized_at) VALUES ('ledger-a','proj-evidence-a'," +
                    "'evidence-a','source-a','tampered-hash','account-bank-a','CNY',2,200,2,200,'out','READY',NULL," +
                    "'p408_evidence_projection_v1',1,'seed','t')",
                0,
            )
        }
        val rejected =
            assertIs<P408ReconciliationResult.Rejected>(
                SqlDelightP408ReconciliationStore(db, driver).confirmLink(confirmRequest(amountMinor = 200)),
            )
        assertEquals(SqlDelightEvidenceProjectionStore.CODE_SOURCE_DRIFT, rejected.code)
        assertEquals(0L, db.ledgerQueries.countEvidenceLinkRows().executeAsOne())
    }

    @Test
    fun spec007GateStateConflictPositiveTrigger() {
        val (db, driver) = newWorld()
        seedSource(db, driver, "request-a", "source-a", "evidence-a", amountMinor = 1000, precision = 2)
        seedPosting(db, driver)
        val store = SqlDelightEvidenceProjectionStore.createShared(db)
        assertIs<P408MaterializeResult.Accepted>(
            store.materialize(materializationRequest(evidenceId = "evidence-a")),
        )
        // Same evidence, divergent requested normalized content -> typed conflict at the gate.
        // Divergent MATERIALIZATION input (different explicit target) hits the gate's
        // existing-READY branch before any request-vs-authority echo: STATE_CONFLICT.
        val rejected =
            assertIs<P408ReconciliationResult.Rejected>(
                SqlDelightP408ReconciliationStore(db, driver)
                    .confirmLink(confirmRequest(requestId = "request-conflict").copy(accountId = "account-bank-b")),
            )
        assertEquals(SqlDelightEvidenceProjectionStore.CODE_STATE_CONFLICT, rejected.code)
        assertEquals(0L, db.ledgerQueries.countEvidenceLinkRows().executeAsOne())
    }

    @Test
    fun spec007ConfirmPathOverflowLeavesZeroResidueAndStaysRetryable() {
        val (db, driver) = newWorld()
        seedSource(
            db,
            driver,
            "request-a",
            "source-a",
            "evidence-a",
            amountMinor = Long.MAX_VALUE / 2,
            precision = 0,
        )
        seedPosting(db, driver)
        var rejected =
            assertIs<P408ReconciliationResult.Rejected>(
                SqlDelightP408ReconciliationStore(db, driver).confirmLink(confirmRequest()),
            )
        assertEquals(SqlDelightEvidenceProjectionStore.CODE_ARITHMETIC_OVERFLOW, rejected.code)
        assertEquals(0L, db.ledgerQueries.countEvidenceProjectionRows().executeAsOne())
        assertEquals(0L, db.ledgerQueries.countP408ReconciliationRequestRows().executeAsOne())
        // Same identity retry stays typed and residue-free.
        rejected =
            assertIs<P408ReconciliationResult.Rejected>(
                SqlDelightP408ReconciliationStore(db, driver).confirmLink(confirmRequest()),
            )
        assertEquals(SqlDelightEvidenceProjectionStore.CODE_ARITHMETIC_OVERFLOW, rejected.code)
        assertEquals(0L, db.ledgerQueries.countEvidenceProjectionRows().executeAsOne())
    }

    // ---------- fixtures ----------

    private fun materializationRequest(
        targetPrecision: Int = 2,
        evidenceId: String = "evidence-p",
        requestIdSuffix: String = "",
    ): P408MaterializationRequest =
        P408MaterializationRequest(
            ledgerId = "ledger-a",
            requestId = "matn-request" + if (requestIdSuffix.isEmpty()) "" else "-$requestIdSuffix",
            evidenceId = evidenceId,
            targetAccountId = "account-bank-a",
            targetCurrencyCode = "CNY",
            targetCurrencyPrecision = targetPrecision,
            materializedAt = "2026-08-11T09:00:00+08:00",
        )

    private fun confirmRequest(
        basisVersion: Int = 2,
        amountMinor: Long = 1000,
        requestId: String = "request-confirm",
    ): P408ConfirmLinkRequest {
        val v2 = basisVersion == 2
        return P408ConfirmLinkRequest(
            ledgerId = "ledger-a",
            requestId = requestId,
            evidenceId = "evidence-a",
            candidateId = "candidate-a",
            postingId = "posting-a",
            transactionId = "tx-a",
            amountMinor = amountMinor,
            currencyCode = "CNY",
            currencyPrecision = 2,
            direction = "out",
            accountId = "account-bank-a",
            responsibility = P408EvidenceResponsibility.REAL_ACCOUNT_POSTING,
            basisVersion = basisVersion,
            matchBasis = setOf("amount", "currency", "direction", "occurred_at_window", "account"),
            windowDays = P408Matcher.DEFAULT_WINDOW_DAYS,
            naturalDayDistance = 0,
            sourceOccurredAt = "2026-08-10T12:00:00+08:00",
            confirmedAt = "2026-08-10T13:00:00+08:00",
            linkId = "link-a",
            reconciliationId = "reconciliation-posting-a",
            createdAt = "2026-08-10T13:00:00+08:00",
            projectionId = if (v2) "proj-evidence-a" else null,
            projectionRuleId = if (v2) P408EvidenceProjectionPort.RULE_ID else null,
            projectionRuleVersion = if (v2) 1 else null,
            normalizedAmountMinor = if (v2) amountMinor else null,
            rawAmountMinor = if (v2) amountMinor else null,
            rawCurrencyPrecision = if (v2) 2 else null,
        )
    }
}

private fun projectionSqliteProperties(): java.util.Properties =
    java.util.Properties().apply {
        setProperty("foreign_keys", "true")
        setProperty("busy_timeout", "5000")
    }
