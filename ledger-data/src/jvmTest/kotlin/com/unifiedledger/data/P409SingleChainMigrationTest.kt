package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.P408ConfirmLinkRequest
import com.unifiedledger.application.P408EvidenceProjectionPort
import com.unifiedledger.application.P408EvidenceResponsibility
import com.unifiedledger.application.P408Matcher
import com.unifiedledger.application.P408ReconciliationResult
import com.unifiedledger.data.db.LedgerDatabase
import java.nio.file.Files
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P4-09 D2 (D-110 implementation spec section 4): the single populated v1 -> v25
 * migration chain. One database walks every live edge with rows inserted at each
 * intermediate version (spec section 4.1 stage table), reopens, proves the guard
 * families and the 61-trigger terminal state, and is then compared row-for-row
 * against a fresh v25 database that explicitly reconstructs the same final row set
 * — including the migration-only sentinel rows — from the named constants below
 * (ruling 4: fresh side constructs with the constants, migrated side first asserts
 * its own sentinel values against the same constants, only then the full-row
 * comparator runs; no aggregation, no SQL ORDER BY dependence). The store-driven
 * confirmLink advance of the v23-seeded PENDING row runs after the data-level
 * comparison as an independent assertion segment on the migrated side only.
 */
class P409SingleChainMigrationTest {
    // Ruling 4 named sentinel constants (migration-only row values).
    private val fundingBackfillState = "UNRESOLVED"
    private val fundingBackfillRuleId = "migration-v24-unresolved"
    private val fundingBackfillGeneratedAt = "migration-v24-unresolved"
    private val migrationV23SeedRequest = "migration-v23-seed"
    private val migrationV23SeedOccurredAt = "schema-v23"

    private val ledger = "ledger-p409-mig"

    // Guard families enumerated name by name from 20.sqm..24.sqm (spec section 4.2);
    // the deduplicated v25 terminal state is 20 + 18 + 15 + 8 = 61 triggers.
    private val importFamilyTriggers = listOf(
        "import_request_guard_update", "import_request_guard_delete",
        "import_source_record_guard_update", "import_source_record_guard_delete",
        "import_evidence_guard_update", "import_evidence_guard_delete",
        "import_candidate_guard_update", "import_candidate_guard_delete",
        "import_candidate_requires_confirmation_guard_update", "import_candidate_requires_confirmation_guard_delete",
        "import_candidate_status_history_guard_update", "import_candidate_status_history_guard_delete",
        "import_candidate_decision_snapshot_guard_update", "import_candidate_decision_snapshot_guard_delete",
        "import_confirmation_guard_update", "import_confirmation_guard_delete",
        "import_receipt_guard_update", "import_receipt_guard_delete",
        "import_status_history_sequence_guard", "import_status_history_transition_guard",
    )
    private val p408Triggers = listOf(
        "reconciliation_request_guard_update", "reconciliation_request_guard_delete",
        "reconciliation_snapshot_guard_update", "reconciliation_snapshot_guard_delete",
        "reconciliation_receipt_guard_update", "reconciliation_receipt_guard_delete",
        "evidence_link_guard_update", "evidence_link_guard_delete",
        "evidence_link_history_guard_update", "evidence_link_history_guard_delete",
        "posting_reconciliation_guard_delete", "posting_reconciliation_history_guard_update",
        "posting_reconciliation_history_guard_delete", "evidence_link_history_sequence_guard",
        "evidence_link_history_transition_guard", "posting_reconciliation_history_sequence_guard",
        "posting_reconciliation_history_link_guard", "posting_reconciliation_update_guard",
    )
    private val duplicateFamilyTriggers = listOf(
        "import_duplicate_candidate_guard_update", "import_duplicate_candidate_guard_delete",
        "import_duplicate_history_guard_update", "import_duplicate_history_guard_delete",
        "import_duplicate_review_request_guard_update", "import_duplicate_review_request_guard_delete",
        "import_duplicate_review_snapshot_guard_update", "import_duplicate_review_snapshot_guard_delete",
        "import_duplicate_review_receipt_guard_update", "import_duplicate_review_receipt_guard_delete",
        "import_duplicate_history_sequence", "import_duplicate_history_terminal",
        "import_duplicate_history_creation_owner", "import_duplicate_history_review_owner",
        "import_duplicate_review_receipt_consistency",
    )
    private val v25NewTriggers = listOf(
        "import_candidate_payment_profile_guard_update", "import_candidate_payment_profile_guard_delete",
        "mixed_payment_group_guard_update", "mixed_payment_group_guard_delete",
        "mixed_payment_group_leg_guard_update", "mixed_payment_group_leg_guard_delete",
        "mixed_payment_group_complete", "mixed_payment_group_leg_before_head",
    )

    @Test
    fun populatedV1ToV25SingleChainReopensGuardedEqualsFreshAndAdvancesSeededRow() {
        val migratedPath = Files.createTempFile("p409-single-chain-migrated-", ".db")
        val freshPath = Files.createTempFile("p409-single-chain-fresh-", ".db")
        val migratedUrl = "jdbc:sqlite:${migratedPath.absolutePathString()}"
        val freshUrl = "jdbc:sqlite:${freshPath.absolutePathString()}"
        try {
            // Stage 0: populated v1 — the core chain plus the tx-existing seed rows.
            DriverManager.getConnection(migratedUrl).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            // Stage 1: v20 — every rg owner, no import_*.
            JdbcSqliteDriver(migratedUrl, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 1, 20)
                // Stage 2: v20 rows — the spine-adjacent silos are in place before the
                // spine owners exist.
                driver.execute(null, "INSERT INTO rg03_operation_request VALUES ('$ledger','rg03-existing','MANUAL_ACCOUNT_TRANSFER')", 0)
                driver.execute(null, "INSERT INTO rg04_import_request VALUES ('$ledger','rg04-existing','IMPORT_SOURCE')", 0)
            }
            // Stage 3: v21 — the spine 9 owners appear.
            JdbcSqliteDriver(migratedUrl, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 20, 21)
                // Stage 4: v21 rows — one ordinary v1 chain in the 15-column shape
                // (no funding columns exist yet).
                driver.execute(null, "INSERT INTO import_request VALUES ('$ledger','request-mig-v1','intake')", 0)
                driver.execute(null, "INSERT INTO import_source_record(ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness, amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token) VALUES ('$ledger','source-mig-v1','request-mig-v1','batch-p409-mig',0,'ordinary_flow_source','sha256:mig-v1',1,'valid_complete',3580,'CNY',2,'2026-08-01T12:00:00+08:00','out','settled')", 0)
                driver.execute(null, "INSERT INTO import_evidence VALUES ('$ledger','evidence-mig-v1','source-mig-v1','source_observation','2026-08-01T12:00:00+08:00')", 0)
                driver.execute(null, "INSERT INTO import_candidate VALUES ('$ledger','candidate-mig-v1','source-mig-v1','ordinary_flow','1.00','ordinary_flow_source',1)", 0)
                driver.execute(null, "INSERT INTO import_candidate_status_history VALUES ('$ledger','candidate-mig-v1',1,'history-mig-v1','pending_confirmation','request-mig-v1','creation')", 0)
            }
            // Stage 5: v22 — the transfer kind CHECK extension.
            JdbcSqliteDriver(migratedUrl, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 21, 22)
                // Stage 6: v22 rows — a complete transfer row, a missing-leg row and the
                // formal ACCOUNT_TRANSFER chain the v23 edge will seed from.
                driver.execute(null, "INSERT INTO import_request VALUES ('$ledger','request-mig-v2','intake')", 0)
                driver.execute(null, "INSERT INTO import_request VALUES ('$ledger','request-mig-v2m','intake')", 0)
                driver.execute(null, "INSERT INTO import_source_record(ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness, amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token) VALUES ('$ledger','source-mig-v2','request-mig-v2','batch-p409-mig',1,'transfer_flow_source','sha256:mig-v2',2,'valid_complete',2000,'CNY',2,'2026-08-05T12:00:00+08:00','out','settled')", 0)
                driver.execute(null, "INSERT INTO import_source_record(ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness, amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token) VALUES ('$ledger','source-mig-v2m','request-mig-v2m','batch-p409-mig',2,'transfer_flow_source_missing_leg','sha256:mig-v2m',2,'valid_complete',1500,'CNY',2,'2026-08-06T12:00:00+08:00','out','settled')", 0)
                driver.execute(null, "INSERT INTO import_evidence VALUES ('$ledger','evidence-mig-v2','source-mig-v2','source_observation','2026-08-05T12:00:00+08:00')", 0)
                driver.execute(null, "INSERT INTO import_evidence VALUES ('$ledger','evidence-mig-v2m','source-mig-v2m','source_observation','2026-08-06T12:00:00+08:00')", 0)
                driver.execute(null, "INSERT INTO import_candidate VALUES ('$ledger','candidate-mig-v2','source-mig-v2','transfer_flow','1.00','transfer_flow_source',1)", 0)
                driver.execute(null, "INSERT INTO import_candidate VALUES ('$ledger','candidate-mig-v2m','source-mig-v2m','transfer_flow_missing_leg','1.00','transfer_flow_source_missing_leg',1)", 0)
                driver.execute(null, "INSERT INTO import_candidate_status_history VALUES ('$ledger','candidate-mig-v2',1,'history-mig-v2','pending_confirmation','request-mig-v2','creation')", 0)
                driver.execute(null, "INSERT INTO import_candidate_status_history VALUES ('$ledger','candidate-mig-v2m',1,'history-mig-v2m','pending_confirmation','request-mig-v2m','creation')", 0)
                driver.execute(null, "INSERT INTO ledger_transaction(transaction_id,ledger_id,kind,canonical_kind) VALUES ('tx-mig-transfer','$ledger','ACCOUNT_TRANSFER',NULL)", 0)
                driver.execute(null, "INSERT INTO posting_set VALUES ('posting-set-mig-transfer','$ledger')", 0)
                driver.execute(null, "INSERT INTO transaction_version(version_id,transaction_id,ledger_id,version_number,posting_set_id,occurred_at,statistics_at,effective_at,note) VALUES ('version-mig-transfer','tx-mig-transfer','$ledger',1,'posting-set-mig-transfer','2026-08-05T12:00:00+08:00','2026-08-05T12:00:00+08:00','2026-08-05T12:00:00+08:00',NULL)", 0)
                driver.execute(null, "INSERT INTO ledger_transaction_current_version VALUES ('tx-mig-transfer','$ledger','version-mig-transfer')", 0)
                driver.execute(null, "INSERT INTO posting VALUES ('posting-mig-transfer-out','posting-set-mig-transfer','$ledger',0,'account-mig-a',-2000,'CNY',2)", 0)
                driver.execute(null, "INSERT INTO posting VALUES ('posting-mig-transfer-in','posting-set-mig-transfer','$ledger',1,'account-mig-b',2000,'CNY',2)", 0)
            }
            // Stage 7: v23 — the P4-08 7 tables plus the seed rows for both transfer
            // postings and the migration-v23-seed request.
            JdbcSqliteDriver(migratedUrl, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 22, 23)
                // Stage 8: v23 rows — the manual link state sits on the missing-leg
                // evidence so the complete-leg evidence stays free for the store segment
                // below (one active link per evidence); the seeded posting_reconciliation
                // rows are never hand-updated (the update guard is already in place; the
                // advance belongs to the store segment).
                driver.execute(null, "INSERT INTO reconciliation_request(ledger_id, request_id, operation, input_fingerprint, outcome) VALUES ('$ledger','request-mig-v3','confirm_link','fp-mig-v3','ACCEPTED')", 0)
                driver.execute(null, "INSERT INTO reconciliation_request_snapshot(ledger_id, request_id, evidence_id, candidate_id, posting_id, transaction_id, amount_minor, currency_code, currency_precision, direction, account_id, responsibility, basis_version, match_basis, window_days, natural_day_distance, source_occurred_at, confirmed_at, human_decision) VALUES ('$ledger','request-mig-v3','evidence-mig-v2m','candidate-mig','posting-mig-transfer-in','tx-mig-transfer',2000,'CNY',2,'in','account-mig-b','destination_asset_posting',1,'account,amount,currency,direction,occurred_at_window',2,0,'2026-08-05T12:00:00+08:00','2026-08-05T13:00:00+08:00','confirm_match')", 0)
                driver.execute(null, "INSERT INTO evidence_link(ledger_id, link_id, evidence_id, posting_id, transaction_id, responsibility, basis_version, match_basis, candidate_id, request_id, created_at) VALUES ('$ledger','link-mig','evidence-mig-v2m','posting-mig-transfer-in','tx-mig-transfer','destination_asset_posting',1,'account,amount,currency,direction,occurred_at_window','candidate-mig','request-mig-v3','2026-08-05T13:00:00+08:00')", 0)
                driver.execute(null, "INSERT INTO evidence_link_history(ledger_id, link_id, sequence, state, reason, request_id, occurred_at) VALUES ('$ledger','link-mig',1,'active','confirmed','request-mig-v3','2026-08-05T13:00:00+08:00')", 0)
                driver.execute(null, "INSERT INTO reconciliation_receipt(ledger_id, request_id, outcome, link_id, reconciliation_id, history_sequence) VALUES ('$ledger','request-mig-v3','ACCEPTED','link-mig','reconciliation-posting-mig-transfer-in',2)", 0)
            }
            // Stage 9: v24 — the duplicate family, the funding columns and the backfill.
            JdbcSqliteDriver(migratedUrl, migrationSqliteProperties()).use { driver ->
                LedgerDatabase(driver).transaction { LedgerDatabase.Schema.migrate(driver, 23, 24) }
                // Stage 10: v24 rows — the spine chain with explicit funding columns
                // first (the duplicate pair's subject-source FK needs the source row),
                // then one duplicate pair.
                driver.execute(null, "INSERT INTO import_request VALUES ('$ledger','request-mig-v4','intake')", 0)
                driver.execute(null, "INSERT INTO import_source_record(ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness, amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token, funding_state, funding_rule_id, funding_rule_version, candidate_generated_at) VALUES ('$ledger','source-mig-v4','request-mig-v4','batch-p409-mig',3,'ordinary_flow_source','sha256:mig-v4',1,'valid_complete',4500,'CNY',2,'2026-08-09T12:00:00+08:00','out','settled','SETTLED','legacy-settled-v1',1,'generated-mig-v4')", 0)
                driver.execute(null, "INSERT INTO import_evidence VALUES ('$ledger','evidence-mig-v4','source-mig-v4','source_observation','2026-08-09T12:00:00+08:00')", 0)
                driver.execute(null, "INSERT INTO import_candidate VALUES ('$ledger','candidate-mig-v4','source-mig-v4','ordinary_flow','1.00','ordinary_flow_source',1)", 0)
                driver.execute(null, "INSERT INTO import_candidate_status_history VALUES ('$ledger','candidate-mig-v4',1,'history-mig-v4','pending_confirmation','request-mig-v4','creation')", 0)
                driver.execute(null, "INSERT INTO import_duplicate_candidate(ledger_id, candidate_id, subject_source_id, possible_existing_source_id, kind, comparison_fingerprint, comparison_snapshot, provenance, confidence, rule_id, rule_version, generated_at, creation_request_id) VALUES ('$ledger','duplicate-mig','source-mig-v4','source-mig-v1','EXACT_BUSINESS_TUPLE','sha256:mig-duplicate','{\"subject\":\"source-mig-v4\"}','source_declared + mechanical_decode + p407_exact_business_tuple_v1','exact','p407_exact_business_tuple_v1',1,'2026-08-10T08:00:00Z','request-mig-v4')", 0)
                driver.execute(null, "INSERT INTO import_duplicate_status_history(ledger_id, candidate_id, sequence, history_id, status, request_id, operation_class) VALUES ('$ledger','duplicate-mig',1,'history-duplicate-mig','DEFERRED','request-mig-v4','creation')", 0)
            }
            // Stage 11: v25 — the credit/mixed structures and the v3 kind rebuild.
            JdbcSqliteDriver(migratedUrl, migrationSqliteProperties()).use { driver ->
                LedgerDatabase(driver).transaction { LedgerDatabase.Schema.migrate(driver, 24, 27) }
                // Stage 12: v25 rows — three v3 kinds with candidates and profiles; the
                // credit expense row exercises the funding-omittable insert shape.
                driver.execute(null, "INSERT INTO import_request VALUES ('$ledger','request-mig-v5c','intake')", 0)
                driver.execute(null, "INSERT INTO import_request VALUES ('$ledger','request-mig-v5r','intake')", 0)
                driver.execute(null, "INSERT INTO import_request VALUES ('$ledger','request-mig-v5m','intake')", 0)
                driver.execute(null, "INSERT INTO import_source_record(ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness, amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token) VALUES ('$ledger','source-mig-v5c','request-mig-v5c','batch-p409-mig',4,'credit_expense_source','sha256:mig-v5c',3,'valid_complete',10000,'CNY',2,'2026-08-11T12:00:00+08:00','out','settled')", 0)
                driver.execute(null, "INSERT INTO import_source_record(ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness, amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token, funding_state, funding_rule_id, funding_rule_version, candidate_generated_at) VALUES ('$ledger','source-mig-v5r','request-mig-v5r','batch-p409-mig',5,'credit_repayment_source','sha256:mig-v5r',3,'valid_complete',5620,'CNY',2,'2026-08-12T12:00:00+08:00','out','settled','SETTLED','legacy-settled-v1',1,'generated-mig-v5r')", 0)
                driver.execute(null, "INSERT INTO import_source_record(ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness, amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token, funding_state, funding_rule_id, funding_rule_version, candidate_generated_at) VALUES ('$ledger','source-mig-v5m','request-mig-v5m','batch-p409-mig',6,'mixed_payment_source','sha256:mig-v5m',3,'valid_complete',1240,'CNY',2,'2026-08-13T12:00:00+08:00','out','settled','SETTLED','legacy-settled-v1',1,'generated-mig-v5m')", 0)
                driver.execute(null, "INSERT INTO import_candidate VALUES ('$ledger','candidate-mig-v5c','source-mig-v5c','credit_expense','1.00','credit_expense_source',1)", 0)
                driver.execute(null, "INSERT INTO import_candidate VALUES ('$ledger','candidate-mig-v5r','source-mig-v5r','credit_repayment','1.00','credit_repayment_source',1)", 0)
                driver.execute(null, "INSERT INTO import_candidate VALUES ('$ledger','candidate-mig-v5m','source-mig-v5m','mixed_payment','1.00','mixed_payment_source',1)", 0)
                driver.execute(null, "INSERT INTO import_candidate_payment_profile(ledger_id, candidate_id, variant, asset_leg_kind_token, credit_leg_kind_token) VALUES ('$ledger','candidate-mig-v5c','credit_expense_direct',NULL,'花呗')", 0)
                driver.execute(null, "INSERT INTO import_candidate_payment_profile(ledger_id, candidate_id, variant, asset_leg_kind_token, credit_leg_kind_token) VALUES ('$ledger','candidate-mig-v5r','credit_repayment','余额宝',NULL)", 0)
                driver.execute(null, "INSERT INTO import_candidate_payment_profile(ledger_id, candidate_id, variant, asset_leg_kind_token, credit_leg_kind_token) VALUES ('$ledger','candidate-mig-v5m','mixed_payment','余额宝','花呗')", 0)
            }

            // Stage 13: reopen on a new driver of the same file.
            JdbcSqliteDriver(migratedUrl, migrationSqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)

                // Inserted rows survive value-for-value (spot rows per generation).
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM ledger_transaction WHERE transaction_id='tx-existing' AND ledger_id='ledger-a'"))
                assertEquals(2L, queryCount(driver, "SELECT count(*) FROM posting WHERE posting_set_id='posting-set-existing'"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM rg03_operation_request WHERE request_id='rg03-existing'"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM rg04_import_request WHERE request_id='rg04-existing'"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM import_source_record WHERE source_id='source-mig-v1' AND amount_minor=3580"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM import_source_record WHERE source_id='source-mig-v2' AND record_kind='transfer_flow_source'"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM import_candidate_payment_profile WHERE candidate_id='candidate-mig-v5m' AND variant='mixed_payment'"))
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM pragma_foreign_key_check"))

                // The migration-only sentinel rows exist with exactly the named
                // constants (ruling 4: independent assertion before any comparison).
                assertEquals(
                    1L,
                    queryCount(
                        driver,
                        "SELECT count(*) FROM import_source_record WHERE source_id='source-mig-v1' AND funding_state='$fundingBackfillState' AND funding_rule_id='$fundingBackfillRuleId' AND candidate_generated_at='$fundingBackfillGeneratedAt'",
                    ),
                )
                assertEquals(
                    1L,
                    queryCount(
                        driver,
                        "SELECT count(*) FROM import_source_record WHERE source_id='source-mig-v2' AND funding_state='$fundingBackfillState' AND candidate_generated_at='$fundingBackfillGeneratedAt'",
                    ),
                )
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM reconciliation_request WHERE request_id='$migrationV23SeedRequest' AND operation='migration_seed'"))
                assertEquals(2L, queryCount(driver, "SELECT count(*) FROM posting_reconciliation WHERE status='PENDING' AND latest_sequence=1"))
                assertEquals(
                    2L,
                    queryCount(
                        driver,
                        "SELECT count(*) FROM posting_reconciliation_history WHERE sequence=1 AND status='PENDING' AND evidence_link_id IS NULL AND request_id='$migrationV23SeedRequest' AND occurred_at='$migrationV23SeedOccurredAt'",
                    ),
                )
                assertEquals(
                    1L,
                    queryCount(
                        driver,
                        "SELECT count(*) FROM import_source_record WHERE source_id='source-mig-v5c' AND funding_state='$fundingBackfillState' AND funding_rule_id='$fundingBackfillRuleId' AND candidate_generated_at='$fundingBackfillGeneratedAt'",
                    ),
                )

                // Guard families, name by name, plus the deduplicated 61-name total.
                (importFamilyTriggers + p408Triggers + duplicateFamilyTriggers + v25NewTriggers).forEach { trigger ->
                    assertEquals(1L, queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name='$trigger'"), trigger)
                }
                val names = (importFamilyTriggers + p408Triggers + duplicateFamilyTriggers + v25NewTriggers).joinToString(",") { "'$it'" }
                assertEquals(61L, queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name IN ($names)"))
                assertEquals(61, importFamilyTriggers.size + p408Triggers.size + duplicateFamilyTriggers.size + v25NewTriggers.size)

                // Append-only probe: the status history is immutable.
                assertFailsWith<SQLException> {
                    driver.execute(null, "UPDATE import_candidate_status_history SET status='confirmed' WHERE ledger_id='$ledger' AND candidate_id='candidate-mig-v1' AND sequence=1", 0)
                }

                // Data-level fresh equivalence (ruling 4).
                buildFreshV25(freshUrl)
                assertEquals(
                    schemaMetadata(freshUrl),
                    schemaMetadata(migratedUrl),
                )
                JdbcSqliteDriver(freshUrl, migrationSqliteProperties()).use { freshDriver ->
                    assertDataLevelEqual(freshDriver, driver)
                }

                // Store segment (migrated side only): the seeded PENDING row advances to
                // CHECKED through SqlDelightP408ReconciliationStore.confirmLink.
                val store = SqlDelightP408ReconciliationStore(database, driver)
                val accepted = assertIs<P408ReconciliationResult.Accepted>(
                    store.confirmLink(
                        P408ConfirmLinkRequest(
                            ledgerId = ledger,
                            requestId = "request-mig-advance",
                            evidenceId = "evidence-mig-v2",
                            candidateId = "candidate-mig",
                            postingId = "posting-mig-transfer-out",
                            transactionId = "tx-mig-transfer",
                            amountMinor = 2000,
                            currencyCode = "CNY",
                            currencyPrecision = 2,
                            direction = "out",
                            accountId = "account-mig-a",
                            responsibility = P408EvidenceResponsibility.REAL_ACCOUNT_POSTING,
                            basisVersion = 2,
                            projectionId = "proj-evidence-mig-v2",
                            projectionRuleId = P408EvidenceProjectionPort.RULE_ID,
                            projectionRuleVersion = 1,
                            normalizedAmountMinor = 2000,
                            rawAmountMinor = 2000,
                            rawCurrencyPrecision = 2,
                            matchBasis = setOf("amount", "currency", "direction", "occurred_at_window", "account"),
                            windowDays = P408Matcher.DEFAULT_WINDOW_DAYS,
                            naturalDayDistance = 0,
                            sourceOccurredAt = "2026-08-05T12:00:00+08:00",
                            confirmedAt = "2026-08-05T13:00:00+08:00",
                            linkId = "link-mig-advance",
                            reconciliationId = "reconciliation-posting-mig-transfer-out",
                            createdAt = "2026-08-05T13:00:00+08:00",
                        ),
                    ),
                )
                assertEquals(2L, accepted.receipt.historySequence)
                val advanced = database.ledgerQueries.selectP408PostingReconciliation(ledger, "posting-mig-transfer-out").executeAsOne()
                assertEquals("CHECKED", advanced.status)
                assertEquals(2L, advanced.latest_sequence)
                assertEquals(
                    1L,
                    queryCount(driver, "SELECT count(*) FROM posting_reconciliation_history WHERE reconciliation_id='reconciliation-posting-mig-transfer-out' AND sequence=2 AND status='CHECKED' AND evidence_link_id='link-mig-advance'"),
                )
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM reconciliation_receipt WHERE request_id='request-mig-advance'"))
            }

            // The advance also survives its own reopen.
            JdbcSqliteDriver(migratedUrl, migrationSqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                val advanced = database.ledgerQueries.selectP408PostingReconciliation(ledger, "posting-mig-transfer-out").executeAsOne()
                assertEquals("CHECKED", advanced.status)
                assertEquals(2L, advanced.latest_sequence)
                assertEquals(
                    1L,
                    queryCount(driver, "SELECT count(*) FROM posting_reconciliation_history WHERE reconciliation_id='reconciliation-posting-mig-transfer-out' AND sequence=2 AND status='CHECKED'"),
                )
            }
        } finally {
            Files.deleteIfExists(migratedPath)
            Files.deleteIfExists(freshPath)
        }
    }

    /**
     * The fresh side of ruling 4: a Schema.create v25 database receiving the same
     * final row set through explicit INSERTs. The migration-only sentinel rows are
     * constructed from the named constants; nothing is copied from the migrated
     * database.
     */
    private fun buildFreshV25(freshUrl: String) {
        JdbcSqliteDriver(freshUrl, migrationSqliteProperties()).use { driver ->
            LedgerDatabase.Schema.create(driver)
            val execute: (String) -> Unit = { sql -> driver.execute(null, sql, 0) }
            // The v1 seed rows, restated with explicit column lists: the v25 table
            // carries the extra nullable confirmation_id column (15.sqm), so the bare
            // 9-value VALUES form of VERSION_ONE_STATEMENTS cannot run on a fresh v25.
            execute("INSERT INTO posting_set VALUES ('posting-set-existing', 'ledger-a')")
            execute("INSERT INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('tx-existing', 'ledger-a', 'EXPENSE')")
            execute(
                "INSERT INTO transaction_version(version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note) " +
                    "VALUES ('version-existing-v1', 'tx-existing', 'ledger-a', 1, 'posting-set-existing', '2026-01-15T00:30:00Z', '2026-01-15T00:30:00Z', '2026-01-15T00:30:00Z', NULL)",
            )
            execute("INSERT INTO ledger_transaction_current_version VALUES ('tx-existing', 'ledger-a', 'version-existing-v1')")
            execute("INSERT INTO posting VALUES ('posting-expense-existing', 'posting-set-existing', 'ledger-a', 0, 'expense-account-breakfast', 3580, 'CNY', 2)")
            execute("INSERT INTO posting VALUES ('posting-bank-existing', 'posting-set-existing', 'ledger-a', 1, 'asset-bank-a', -3580, 'CNY', 2)")
            // Silo rows.
            execute("INSERT INTO rg03_operation_request VALUES ('$ledger','rg03-existing','MANUAL_ACCOUNT_TRANSFER')")
            execute("INSERT INTO rg04_import_request VALUES ('$ledger','rg04-existing','IMPORT_SOURCE')")
            // v21/v22 spine rows: funding columns written with the v24 backfill constants.
            execute("INSERT INTO import_request VALUES ('$ledger','request-mig-v1','intake')")
            execute("INSERT INTO import_source_record(ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness, amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token, funding_state, funding_rule_id, funding_rule_version, candidate_generated_at) VALUES ('$ledger','source-mig-v1','request-mig-v1','batch-p409-mig',0,'ordinary_flow_source','sha256:mig-v1',1,'valid_complete',3580,'CNY',2,'2026-08-01T12:00:00+08:00','out','settled','$fundingBackfillState','$fundingBackfillRuleId',1,'$fundingBackfillGeneratedAt')")
            execute("INSERT INTO import_evidence VALUES ('$ledger','evidence-mig-v1','source-mig-v1','source_observation','2026-08-01T12:00:00+08:00')")
            execute("INSERT INTO import_candidate VALUES ('$ledger','candidate-mig-v1','source-mig-v1','ordinary_flow','1.00','ordinary_flow_source',1)")
            execute("INSERT INTO import_candidate_status_history VALUES ('$ledger','candidate-mig-v1',1,'history-mig-v1','pending_confirmation','request-mig-v1','creation')")
            execute("INSERT INTO import_request VALUES ('$ledger','request-mig-v2','intake')")
            execute("INSERT INTO import_request VALUES ('$ledger','request-mig-v2m','intake')")
            execute("INSERT INTO import_source_record(ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness, amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token, funding_state, funding_rule_id, funding_rule_version, candidate_generated_at) VALUES ('$ledger','source-mig-v2','request-mig-v2','batch-p409-mig',1,'transfer_flow_source','sha256:mig-v2',2,'valid_complete',2000,'CNY',2,'2026-08-05T12:00:00+08:00','out','settled','$fundingBackfillState','$fundingBackfillRuleId',1,'$fundingBackfillGeneratedAt')")
            execute("INSERT INTO import_source_record(ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness, amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token, funding_state, funding_rule_id, funding_rule_version, candidate_generated_at) VALUES ('$ledger','source-mig-v2m','request-mig-v2m','batch-p409-mig',2,'transfer_flow_source_missing_leg','sha256:mig-v2m',2,'valid_complete',1500,'CNY',2,'2026-08-06T12:00:00+08:00','out','settled','$fundingBackfillState','$fundingBackfillRuleId',1,'$fundingBackfillGeneratedAt')")
            execute("INSERT INTO import_evidence VALUES ('$ledger','evidence-mig-v2','source-mig-v2','source_observation','2026-08-05T12:00:00+08:00')")
            execute("INSERT INTO import_evidence VALUES ('$ledger','evidence-mig-v2m','source-mig-v2m','source_observation','2026-08-06T12:00:00+08:00')")
            execute("INSERT INTO import_candidate VALUES ('$ledger','candidate-mig-v2','source-mig-v2','transfer_flow','1.00','transfer_flow_source',1)")
            execute("INSERT INTO import_candidate VALUES ('$ledger','candidate-mig-v2m','source-mig-v2m','transfer_flow_missing_leg','1.00','transfer_flow_source_missing_leg',1)")
            execute("INSERT INTO import_candidate_status_history VALUES ('$ledger','candidate-mig-v2',1,'history-mig-v2','pending_confirmation','request-mig-v2','creation')")
            execute("INSERT INTO import_candidate_status_history VALUES ('$ledger','candidate-mig-v2m',1,'history-mig-v2m','pending_confirmation','request-mig-v2m','creation')")
            execute("INSERT INTO ledger_transaction(transaction_id,ledger_id,kind,canonical_kind) VALUES ('tx-mig-transfer','$ledger','ACCOUNT_TRANSFER',NULL)")
            execute("INSERT INTO posting_set VALUES ('posting-set-mig-transfer','$ledger')")
            execute("INSERT INTO transaction_version(version_id,transaction_id,ledger_id,version_number,posting_set_id,occurred_at,statistics_at,effective_at,note) VALUES ('version-mig-transfer','tx-mig-transfer','$ledger',1,'posting-set-mig-transfer','2026-08-05T12:00:00+08:00','2026-08-05T12:00:00+08:00','2026-08-05T12:00:00+08:00',NULL)")
            execute("INSERT INTO ledger_transaction_current_version VALUES ('tx-mig-transfer','$ledger','version-mig-transfer')")
            execute("INSERT INTO posting VALUES ('posting-mig-transfer-out','posting-set-mig-transfer','$ledger',0,'account-mig-a',-2000,'CNY',2)")
            execute("INSERT INTO posting VALUES ('posting-mig-transfer-in','posting-set-mig-transfer','$ledger',1,'account-mig-b',2000,'CNY',2)")
            // The v23 migration seed rows, explicitly reconstructed.
            execute("INSERT INTO reconciliation_request(ledger_id, request_id, operation, input_fingerprint, outcome) VALUES ('$ledger','$migrationV23SeedRequest','migration_seed','schema-v23-seed','ACCEPTED')")
            execute("INSERT INTO posting_reconciliation(ledger_id, reconciliation_id, posting_id, status, latest_sequence) VALUES ('$ledger','reconciliation-posting-mig-transfer-out','posting-mig-transfer-out','PENDING',1)")
            execute("INSERT INTO posting_reconciliation(ledger_id, reconciliation_id, posting_id, status, latest_sequence) VALUES ('$ledger','reconciliation-posting-mig-transfer-in','posting-mig-transfer-in','PENDING',1)")
            execute("INSERT INTO posting_reconciliation_history(ledger_id, reconciliation_id, sequence, status, evidence_link_id, request_id, occurred_at) VALUES ('$ledger','reconciliation-posting-mig-transfer-out',1,'PENDING',NULL,'$migrationV23SeedRequest','$migrationV23SeedOccurredAt')")
            execute("INSERT INTO posting_reconciliation_history(ledger_id, reconciliation_id, sequence, status, evidence_link_id, request_id, occurred_at) VALUES ('$ledger','reconciliation-posting-mig-transfer-in',1,'PENDING',NULL,'$migrationV23SeedRequest','$migrationV23SeedOccurredAt')")
            // The v23 hand-written P4-08 link rows (on the missing-leg evidence; see stage 8).
            execute("INSERT INTO reconciliation_request(ledger_id, request_id, operation, input_fingerprint, outcome) VALUES ('$ledger','request-mig-v3','confirm_link','fp-mig-v3','ACCEPTED')")
            execute("INSERT INTO reconciliation_request_snapshot(ledger_id, request_id, evidence_id, candidate_id, posting_id, transaction_id, amount_minor, currency_code, currency_precision, direction, account_id, responsibility, basis_version, match_basis, window_days, natural_day_distance, source_occurred_at, confirmed_at, human_decision, projection_id, projection_rule_id, projection_rule_version, normalized_amount_minor, raw_amount_minor, raw_currency_precision) VALUES ('$ledger','request-mig-v3','evidence-mig-v2m','candidate-mig','posting-mig-transfer-in','tx-mig-transfer',2000,'CNY',2,'in','account-mig-b','destination_asset_posting',1,'account,amount,currency,direction,occurred_at_window',2,0,'2026-08-05T12:00:00+08:00','2026-08-05T13:00:00+08:00','confirm_match',NULL,NULL,NULL,2000,2000,2)")
            execute("INSERT INTO evidence_link(ledger_id, link_id, evidence_id, posting_id, transaction_id, responsibility, basis_version, match_basis, candidate_id, request_id, created_at) VALUES ('$ledger','link-mig','evidence-mig-v2m','posting-mig-transfer-in','tx-mig-transfer','destination_asset_posting',1,'account,amount,currency,direction,occurred_at_window','candidate-mig','request-mig-v3','2026-08-05T13:00:00+08:00')")
            execute("INSERT INTO evidence_link_history(ledger_id, link_id, sequence, state, reason, request_id, occurred_at) VALUES ('$ledger','link-mig',1,'active','confirmed','request-mig-v3','2026-08-05T13:00:00+08:00')")
            execute("INSERT INTO reconciliation_receipt(ledger_id, request_id, outcome, link_id, reconciliation_id, history_sequence) VALUES ('$ledger','request-mig-v3','ACCEPTED','link-mig','reconciliation-posting-mig-transfer-in',2)")
            // v24 rows.
            execute("INSERT INTO import_request VALUES ('$ledger','request-mig-v4','intake')")
            execute("INSERT INTO import_source_record(ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness, amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token, funding_state, funding_rule_id, funding_rule_version, candidate_generated_at) VALUES ('$ledger','source-mig-v4','request-mig-v4','batch-p409-mig',3,'ordinary_flow_source','sha256:mig-v4',1,'valid_complete',4500,'CNY',2,'2026-08-09T12:00:00+08:00','out','settled','SETTLED','legacy-settled-v1',1,'generated-mig-v4')")
            execute("INSERT INTO import_evidence VALUES ('$ledger','evidence-mig-v4','source-mig-v4','source_observation','2026-08-09T12:00:00+08:00')")
            execute("INSERT INTO import_candidate VALUES ('$ledger','candidate-mig-v4','source-mig-v4','ordinary_flow','1.00','ordinary_flow_source',1)")
            execute("INSERT INTO import_candidate_status_history VALUES ('$ledger','candidate-mig-v4',1,'history-mig-v4','pending_confirmation','request-mig-v4','creation')")
            execute("INSERT INTO import_duplicate_candidate(ledger_id, candidate_id, subject_source_id, possible_existing_source_id, kind, comparison_fingerprint, comparison_snapshot, provenance, confidence, rule_id, rule_version, generated_at, creation_request_id) VALUES ('$ledger','duplicate-mig','source-mig-v4','source-mig-v1','EXACT_BUSINESS_TUPLE','sha256:mig-duplicate','{\"subject\":\"source-mig-v4\"}','source_declared + mechanical_decode + p407_exact_business_tuple_v1','exact','p407_exact_business_tuple_v1',1,'2026-08-10T08:00:00Z','request-mig-v4')")
            execute("INSERT INTO import_duplicate_status_history(ledger_id, candidate_id, sequence, history_id, status, request_id, operation_class) VALUES ('$ledger','duplicate-mig',1,'history-duplicate-mig','DEFERRED','request-mig-v4','creation')")
            // v25 rows (the credit row uses the same default-sentinel funding shape as
            // the migrated side's funding-omitting insert).
            execute("INSERT INTO import_request VALUES ('$ledger','request-mig-v5c','intake')")
            execute("INSERT INTO import_request VALUES ('$ledger','request-mig-v5r','intake')")
            execute("INSERT INTO import_request VALUES ('$ledger','request-mig-v5m','intake')")
            execute("INSERT INTO import_source_record(ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness, amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token, funding_state, funding_rule_id, funding_rule_version, candidate_generated_at) VALUES ('$ledger','source-mig-v5c','request-mig-v5c','batch-p409-mig',4,'credit_expense_source','sha256:mig-v5c',3,'valid_complete',10000,'CNY',2,'2026-08-11T12:00:00+08:00','out','settled','$fundingBackfillState','$fundingBackfillRuleId',1,'$fundingBackfillGeneratedAt')")
            execute("INSERT INTO import_source_record(ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness, amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token, funding_state, funding_rule_id, funding_rule_version, candidate_generated_at) VALUES ('$ledger','source-mig-v5r','request-mig-v5r','batch-p409-mig',5,'credit_repayment_source','sha256:mig-v5r',3,'valid_complete',5620,'CNY',2,'2026-08-12T12:00:00+08:00','out','settled','SETTLED','legacy-settled-v1',1,'generated-mig-v5r')")
            execute("INSERT INTO import_source_record(ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness, amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token, funding_state, funding_rule_id, funding_rule_version, candidate_generated_at) VALUES ('$ledger','source-mig-v5m','request-mig-v5m','batch-p409-mig',6,'mixed_payment_source','sha256:mig-v5m',3,'valid_complete',1240,'CNY',2,'2026-08-13T12:00:00+08:00','out','settled','SETTLED','legacy-settled-v1',1,'generated-mig-v5m')")
            execute("INSERT INTO import_candidate VALUES ('$ledger','candidate-mig-v5c','source-mig-v5c','credit_expense','1.00','credit_expense_source',1)")
            execute("INSERT INTO import_candidate VALUES ('$ledger','candidate-mig-v5r','source-mig-v5r','credit_repayment','1.00','credit_repayment_source',1)")
            execute("INSERT INTO import_candidate VALUES ('$ledger','candidate-mig-v5m','source-mig-v5m','mixed_payment','1.00','mixed_payment_source',1)")
            execute("INSERT INTO import_candidate_payment_profile(ledger_id, candidate_id, variant, asset_leg_kind_token, credit_leg_kind_token) VALUES ('$ledger','candidate-mig-v5c','credit_expense_direct',NULL,'花呗')")
            execute("INSERT INTO import_candidate_payment_profile(ledger_id, candidate_id, variant, asset_leg_kind_token, credit_leg_kind_token) VALUES ('$ledger','candidate-mig-v5r','credit_repayment','余额宝',NULL)")
            execute("INSERT INTO import_candidate_payment_profile(ledger_id, candidate_id, variant, asset_leg_kind_token, credit_leg_kind_token) VALUES ('$ledger','candidate-mig-v5m','mixed_payment','余额宝','花呗')")
        }
    }

    // ---------- data-level equivalence projection (ruling 4 / section 4.3) ----------

    private val rowComparator = Comparator<List<Any?>> { left, right ->
        val size = maxOf(left.size, right.size)
        for (index in 0 until size) {
            val l = left.getOrNull(index)
            val r = right.getOrNull(index)
            val compare = when {
                l == null && r == null -> 0
                l == null -> -1
                r == null -> 1
                else -> l.toString().compareTo(r.toString())
            }
            if (compare != 0) return@Comparator compare
        }
        0
    }

    private fun selectRows(driver: JdbcSqliteDriver, sql: String, longColumns: List<Boolean>): List<List<Any?>> = driver.executeQuery(
        null, sql,
        { cursor ->
            val rows = mutableListOf<List<Any?>>()
            while (cursor.next().value) {
                rows += longColumns.mapIndexed { index, isLong ->
                    if (isLong) cursor.getLong(index) else cursor.getString(index)
                }
            }
            app.cash.sqldelight.db.QueryResult.Value(rows.toList())
        },
        0,
    ).value

    private fun projections(): List<Triple<String, String, List<Boolean>>> = listOf(
        Triple("ledger_transaction", "SELECT transaction_id, ledger_id, kind, canonical_kind FROM ledger_transaction", listOf(false, false, false, false)),
        Triple("posting_set", "SELECT posting_set_id, ledger_id FROM posting_set", listOf(false, false)),
        Triple("transaction_version", "SELECT version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note, confirmation_id FROM transaction_version", listOf(false, false, false, true, false, false, false, false, false, false)),
        Triple("ledger_transaction_current_version", "SELECT transaction_id, ledger_id, current_version_id FROM ledger_transaction_current_version", listOf(false, false, false)),
        Triple("posting", "SELECT posting_id, posting_set_id, ledger_id, posting_index, account_id, amount_minor, currency_code, currency_precision FROM posting", listOf(false, false, false, true, false, true, false, true)),
        Triple("rg03_operation_request", "SELECT ledger_id, request_id, action_type FROM rg03_operation_request", listOf(false, false, false)),
        Triple("rg04_import_request", "SELECT ledger_id, request_id, action_type FROM rg04_import_request", listOf(false, false, false)),
        Triple("import_request", "SELECT ledger_id, request_id, operation FROM import_request", listOf(false, false, false)),
        Triple(
            "import_source_record",
            "SELECT ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness, amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token, funding_state, funding_rule_id, funding_rule_version, candidate_generated_at FROM import_source_record",
            listOf(false, false, false, false, true, false, false, true, false, true, false, true, false, false, false, false, false, true, false),
        ),
        Triple("import_evidence", "SELECT ledger_id, evidence_id, source_id, evidence_kind, observed_at FROM import_evidence", listOf(false, false, false, false, false)),
        Triple("import_candidate", "SELECT ledger_id, candidate_id, source_id, candidate_kind, confidence, rule, rule_version FROM import_candidate", listOf(false, false, false, false, false, false, true)),
        Triple("import_candidate_requires_confirmation", "SELECT ledger_id, candidate_id, requirement_index, requirement FROM import_candidate_requires_confirmation", listOf(false, false, true, false)),
        Triple("import_candidate_status_history", "SELECT ledger_id, candidate_id, sequence, status_id, status, request_id, operation_class FROM import_candidate_status_history", listOf(false, false, true, false, false, false, false)),
        Triple(
            "import_candidate_decision_snapshot",
            "SELECT ledger_id, request_id, decision, candidate_id, expected_content_hash, category_id, funding_account_id, from_account_id, to_account_id, credit_liability_account_id, asset_account_id, original_transaction_id, asset_leg_minor, credit_leg_minor, explicit_confirmed_at FROM import_candidate_decision_snapshot",
            listOf(false, false, false, false, false, false, false, false, false, false, false, false, true, true, false),
        ),
        Triple("import_confirmation", "SELECT ledger_id, confirmation_id, request_id, candidate_id, status_id, transaction_id, operation_class, confirmed_at FROM import_confirmation", listOf(false, false, false, false, false, false, false, false)),
        Triple("import_receipt", "SELECT ledger_id, request_id, outcome, source_id, evidence_id, candidate_id, confirmation_id, transaction_id FROM import_receipt", listOf(false, false, false, false, false, false, false, false)),
        Triple("import_candidate_payment_profile", "SELECT ledger_id, candidate_id, variant, asset_leg_kind_token, credit_leg_kind_token FROM import_candidate_payment_profile", listOf(false, false, false, false, false)),
        Triple(
            "import_duplicate_candidate",
            "SELECT ledger_id, candidate_id, subject_source_id, possible_existing_source_id, kind, comparison_fingerprint, comparison_snapshot, provenance, confidence, rule_id, rule_version, generated_at, creation_request_id FROM import_duplicate_candidate",
            listOf(false, false, false, false, false, false, false, false, false, false, true, false, false),
        ),
        Triple("import_duplicate_status_history", "SELECT ledger_id, candidate_id, sequence, history_id, status, request_id, operation_class FROM import_duplicate_status_history", listOf(false, false, true, false, false, false, false)),
        Triple("import_duplicate_review_request", "SELECT ledger_id, request_id, operation, input_fingerprint, outcome, reason_code FROM import_duplicate_review_request", listOf(false, false, false, false, false, false)),
        Triple("import_duplicate_review_snapshot", "SELECT ledger_id, request_id, candidate_id, expected_comparison_fingerprint, decision, reason_token, reviewed_at, reviewer_reference, generated_at, review_id FROM import_duplicate_review_snapshot", listOf(false, false, false, false, false, false, false, false, false, false)),
        Triple("import_duplicate_review_receipt", "SELECT ledger_id, request_id, candidate_id, review_id, history_id, outcome FROM import_duplicate_review_receipt", listOf(false, false, false, false, false, false)),
        Triple("reconciliation_request", "SELECT ledger_id, request_id, operation, input_fingerprint, outcome, reason_code FROM reconciliation_request", listOf(false, false, false, false, false, false)),
        Triple(
            "reconciliation_request_snapshot",
            "SELECT ledger_id, request_id, evidence_id, candidate_id, posting_id, transaction_id, amount_minor, currency_code, currency_precision, direction, account_id, responsibility, basis_version, match_basis, window_days, natural_day_distance, source_occurred_at, confirmed_at, human_decision FROM reconciliation_request_snapshot",
            listOf(false, false, false, false, false, false, true, false, true, false, false, false, true, false, true, true, false, false, false),
        ),
        Triple("evidence_link", "SELECT ledger_id, link_id, evidence_id, posting_id, transaction_id, responsibility, basis_version, match_basis, candidate_id, request_id, created_at FROM evidence_link", listOf(false, false, false, false, false, false, true, false, false, false, false)),
        Triple("evidence_link_history", "SELECT ledger_id, link_id, sequence, state, reason, request_id, occurred_at FROM evidence_link_history", listOf(false, false, true, false, false, false, false)),
        Triple("posting_reconciliation", "SELECT ledger_id, reconciliation_id, posting_id, status, latest_sequence FROM posting_reconciliation", listOf(false, false, false, false, true)),
        Triple("posting_reconciliation_history", "SELECT ledger_id, reconciliation_id, sequence, status, evidence_link_id, request_id, occurred_at FROM posting_reconciliation_history", listOf(false, false, true, false, false, false, false)),
        Triple("reconciliation_receipt", "SELECT ledger_id, request_id, outcome, link_id, reconciliation_id, history_sequence FROM reconciliation_receipt", listOf(false, false, false, false, false, true)),
        Triple("mixed_payment_group", "SELECT ledger_id, group_id, candidate_id, transaction_id, request_id, total_minor, generated_at FROM mixed_payment_group", listOf(false, false, false, false, false, true, false)),
        Triple("mixed_payment_group_leg", "SELECT ledger_id, group_id, leg_index, leg_class, account_id, amount_minor FROM mixed_payment_group_leg", listOf(false, false, true, false, false, true)),
    )

    private fun assertDataLevelEqual(freshDriver: JdbcSqliteDriver, migratedDriver: JdbcSqliteDriver) {
        projections().forEach { (table, sql, longColumns) ->
            val fresh = selectRows(freshDriver, sql, longColumns).sortedWith(rowComparator)
            val migrated = selectRows(migratedDriver, sql, longColumns).sortedWith(rowComparator)
            assertEquals(fresh.size, migrated.size, "$table row count (fresh = migrated)")
            assertEquals(fresh, migrated, "$table rows (fresh = migrated, full-row comparator order)")
        }
    }

    // ---------- local helper copies (LedgerDatabaseMigrationTest.kt :2711/:2757/:2839) ----------

    private fun queryCount(driver: JdbcSqliteDriver, sql: String): Long = driver.executeQuery(
        null,
        sql,
        { cursor ->
            check(cursor.next().value)
            app.cash.sqldelight.db.QueryResult.Value(requireNotNull(cursor.getLong(0)))
        },
        0,
    ).value

    private fun migrationSqliteProperties(): Properties = Properties().apply {
        setProperty("foreign_keys", "true")
        setProperty("busy_timeout", "5000")
    }

    private data class SchemaMetadata(
        val objects: List<String>,
        val foreignKeys: List<String>,
        val indexes: List<String>,
    )

    private fun schemaMetadata(url: String): SchemaMetadata =
        DriverManager.getConnection(url).use { connection ->
            val objects = buildList {
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        """
                        SELECT type, name, tbl_name, sql
                        FROM sqlite_master
                        WHERE name NOT LIKE 'sqlite_%' AND sql IS NOT NULL
                        ORDER BY type, name
                        """.trimIndent(),
                    ).use { rows ->
                        while (rows.next()) {
                            add(
                                listOf(
                                    rows.getString("type"),
                                    rows.getString("name"),
                                    rows.getString("tbl_name"),
                                    normalizeSql(rows.getString("sql")),
                                ).joinToString("|"),
                            )
                        }
                    }
                }
            }
            val tableNames = objects.asSequence()
                .filter { it.startsWith("table|") }
                .map { it.substringAfter('|').substringBefore('|') }
                .toList()
            val foreignKeys = buildList {
                tableNames.forEach { table ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("PRAGMA foreign_key_list('$table')").use { rows ->
                            while (rows.next()) {
                                add(
                                    listOf(
                                        table,
                                        rows.getInt("id"),
                                        rows.getInt("seq"),
                                        rows.getString("table"),
                                        rows.getString("from"),
                                        rows.getString("to"),
                                        rows.getString("on_update"),
                                        rows.getString("on_delete"),
                                        rows.getString("match"),
                                    ).joinToString("|"),
                                )
                            }
                        }
                    }
                }
            }.sorted()
            val indexes = buildList {
                tableNames.forEach { table ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("PRAGMA index_list('$table')").use { rows ->
                            while (rows.next()) {
                                add(
                                    listOf(
                                        table,
                                        rows.getString("name"),
                                        rows.getInt("unique"),
                                        rows.getString("origin"),
                                        rows.getInt("partial"),
                                    ).joinToString("|"),
                                )
                            }
                        }
                    }
                }
            }.sorted()
            SchemaMetadata(objects, foreignKeys, indexes)
        }

    private fun normalizeSql(sql: String): String =
        sql.replace(Regex("\\s+"), " ").trim().replace("( ", "(").replace(" )", ")")
            .replace("\"rg07_operation\"", "rg07_operation")
}
