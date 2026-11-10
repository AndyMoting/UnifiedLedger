package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.DEFAULT_HIDDEN_EXPENSE_ACCOUNT_ID
import com.unifiedledger.application.DEFAULT_MANAGEABLE_ACCOUNT_ID
import com.unifiedledger.application.P408ReconciliationStatus
import com.unifiedledger.application.P801AccountReconciliationCursor
import com.unifiedledger.application.P801AccountReconciliationPageResult
import com.unifiedledger.application.P801AccountReconciliationStatusTotal
import com.unifiedledger.application.P801AccountReconciliationSummaryResult
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P8-01a account reconciliation read adapter evidence (D-308 items 6/8; spec section 2.3). Drives
 * the real SQL over a real in-memory SQLite database, so removing the composite-mark join, the
 * current-version/effective joins, the keyset bound or the integer `sum()` turns a specific
 * assertion red:
 *
 *  - the frozen `occurred_at DESC, posting_id ASC` order holds, including the posting-id tie-break
 *    inside one instant, and a keyset cursor continues the page exactly once (no skip, no repeat);
 *  - a leg on a non-real account (the fee/income leg of the very same transfer) and a leg of the
 *    same ledger on another account never enter the requested account's queue;
 *  - a queue leg without a `posting_reconciliation` row reads `PENDING`, a stored status is read
 *    as-is, and only an ACTIVE latest evidence-link history sets `hasActiveEvidenceLink`;
 *  - the summary folds exact integer minor units per (currency, status) with its row count;
 *  - an absent group is absent and never a zero row, while a real zero amount is a real group with
 *    one row (the D-089 distinction both ways).
 *
 * All data synthetic and anonymous, with fixed instants.
 */
class SqlDelightP801AccountReconciliationReadAdapterTest {
    private val ledgerId = LedgerId("ledger-p801-read")
    private val assetAccountId = AccountId(DEFAULT_MANAGEABLE_ACCOUNT_ID)
    private val expenseAccountId = AccountId(DEFAULT_HIDDEN_EXPENSE_ACCOUNT_ID)
    private val otherAssetAccountId = AccountId("asset-second")

    @Test
    fun queueOrdersByOccurredAtDescThenPostingIdAscAndContinuesByKeyset() {
        withDatabase { _, driver, adapter ->
            seedTransaction(driver, "tx-old", "2026-03-01T02:00:00Z", legs = listOf(SeededLeg("posting-old", assetAccountId.value, -1_000L)))
            // Same instant, two legs of one transaction: the tie-break is posting_id ASC, never the
            // insertion (posting_index) order.
            seedTransaction(
                driver,
                "tx-tie",
                "2026-03-05T02:00:00Z",
                note = "tie note",
                legs =
                    listOf(
                        SeededLeg("posting-z", assetAccountId.value, -200L),
                        SeededLeg("posting-a", assetAccountId.value, -300L),
                    ),
            )
            seedTransaction(driver, "tx-new", "2026-03-09T02:00:00Z", legs = listOf(SeededLeg("posting-new", assetAccountId.value, -4_000L)))

            val first =
                assertIs<P801AccountReconciliationPageResult.Success>(
                    adapter.loadAccountReconciliationPage(ledgerId, assetAccountId, after = null, pageSize = 2),
                ).page
            assertEquals(listOf("posting-new", "posting-a"), first.rows.map { it.postingId.value })
            assertEquals(
                P801AccountReconciliationCursor(occurredAtStorageValue = "2026-03-05T02:00:00Z", postingId = PostingId("posting-a")),
                first.nextCursor,
            )
            // A row carries the leg's own exact facts, not an aggregate: signed minor units, the
            // currency, the current version's note and the transaction's effective kind.
            val tieLeg = first.rows[1]
            assertEquals(TransactionId("tx-tie"), tieLeg.transactionId)
            assertEquals(Instant.parse("2026-03-05T02:00:00Z"), tieLeg.occurredAt)
            assertEquals("tie note", tieLeg.note)
            assertEquals(assetAccountId, tieLeg.accountId)
            assertEquals(-300L, tieLeg.amountMinor)
            assertEquals(CurrencyUnit("CNY", 2), tieLeg.currency)
            assertEquals(P408ReconciliationStatus.PENDING, tieLeg.status)
            assertEquals(false, tieLeg.hasActiveEvidenceLink)
            assertEquals(TransactionKind.ACCOUNT_TRANSFER, tieLeg.transactionKind)

            val second =
                assertIs<P801AccountReconciliationPageResult.Success>(
                    adapter.loadAccountReconciliationPage(ledgerId, assetAccountId, after = first.nextCursor, pageSize = 2),
                ).page
            assertEquals(listOf("posting-z", "posting-old"), second.rows.map { it.postingId.value })
            assertNull(second.nextCursor)
        }
    }

    @Test
    fun onlyTheCompositeMarkedLegsOfTheRequestedAccountEnterTheQueue() {
        withDatabase { _, driver, adapter ->
            seedCatalogAccount(driver, otherAssetAccountId.value, kind = "ASSET", ownedByUser = true, realAccount = true)
            // One transfer: its real asset leg and its fee leg on the hidden expense account.
            seedTransaction(
                driver,
                "tx-transfer",
                "2026-03-05T02:00:00Z",
                legs =
                    listOf(
                        SeededLeg("posting-transfer-out", assetAccountId.value, -6_000L),
                        SeededLeg("posting-transfer-fee", expenseAccountId.value, 100L),
                    ),
            )
            // The destination leg of the same ledger, on another real asset account.
            seedTransaction(driver, "tx-other", "2026-03-06T02:00:00Z", legs = listOf(SeededLeg("posting-other", otherAssetAccountId.value, 5_900L)))

            val assetQueue =
                assertIs<P801AccountReconciliationPageResult.Success>(
                    adapter.loadAccountReconciliationPage(ledgerId, assetAccountId, after = null),
                ).page
            assertEquals(listOf("posting-transfer-out"), assetQueue.rows.map { it.postingId.value })
            // The fee leg never appears: the composite mark excludes EXPENSE (and INCOME) accounts,
            // so the hidden fee leg of the very same transaction stays out of every queue.
            val expenseQueue =
                assertIs<P801AccountReconciliationPageResult.Success>(
                    adapter.loadAccountReconciliationPage(ledgerId, expenseAccountId, after = null),
                ).page
            assertTrue(expenseQueue.rows.isEmpty())
            val otherQueue =
                assertIs<P801AccountReconciliationPageResult.Success>(
                    adapter.loadAccountReconciliationPage(ledgerId, otherAssetAccountId, after = null),
                ).page
            assertEquals(listOf("posting-other"), otherQueue.rows.map { it.postingId.value })
        }
    }

    @Test
    fun pendingIsReadWithoutAReconciliationRowAndOnlyAnActiveLinkCounts() {
        withDatabase { _, driver, adapter ->
            seedTransaction(driver, "tx-plain", "2026-03-01T02:00:00Z", legs = listOf(SeededLeg("posting-plain", assetAccountId.value, -1_000L)))
            seedTransaction(driver, "tx-checked", "2026-03-02T02:00:00Z", legs = listOf(SeededLeg("posting-checked", assetAccountId.value, -2_000L)))
            seedReconciliation(driver, "posting-checked", "CHECKED")
            seedTransaction(driver, "tx-linked", "2026-03-03T02:00:00Z", legs = listOf(SeededLeg("posting-linked", assetAccountId.value, -3_000L)))
            seedEvidenceLink(driver, "tx-linked", "posting-linked", active = true)
            seedTransaction(driver, "tx-invalidated", "2026-03-04T02:00:00Z", legs = listOf(SeededLeg("posting-invalidated", assetAccountId.value, -4_000L)))
            seedEvidenceLink(driver, "tx-invalidated", "posting-invalidated", active = false)

            val rows =
                assertIs<P801AccountReconciliationPageResult.Success>(
                    adapter.loadAccountReconciliationPage(ledgerId, assetAccountId, after = null),
                ).page.rows
            assertEquals(
                listOf("posting-invalidated", "posting-linked", "posting-checked", "posting-plain"),
                rows.map { it.postingId.value },
            )
            // An invalidated latest history is not an active link; an active one is.
            assertEquals(false, rows[0].hasActiveEvidenceLink)
            assertEquals(true, rows[1].hasActiveEvidenceLink)
            // A stored status is read as-is; a leg with no row at all is PENDING (待对账).
            assertEquals(P408ReconciliationStatus.CHECKED, rows[2].status)
            assertEquals(P408ReconciliationStatus.PENDING, rows[1].status)
            assertEquals(P408ReconciliationStatus.PENDING, rows[3].status)
        }
    }

    @Test
    fun theSummarySumsExactMinorUnitsPerCurrencyAndStatus() {
        withDatabase { _, driver, adapter ->
            // Two PENDING CNY legs on one account must fold into one group; the CHECKED CNY leg and
            // the USD leg stay separate groups, so the grouping key really carries both dimensions.
            seedTransaction(driver, "tx-p1", "2026-03-01T02:00:00Z", legs = listOf(SeededLeg("posting-p1", assetAccountId.value, -5_000L)))
            seedTransaction(driver, "tx-p2", "2026-03-02T02:00:00Z", legs = listOf(SeededLeg("posting-p2", assetAccountId.value, -1_000L)))
            seedTransaction(driver, "tx-c1", "2026-03-03T02:00:00Z", legs = listOf(SeededLeg("posting-c1", assetAccountId.value, -3_000L)))
            seedReconciliation(driver, "posting-c1", "CHECKED")
            seedTransaction(
                driver,
                "tx-usd",
                "2026-03-04T02:00:00Z",
                legs = listOf(SeededLeg("posting-usd", assetAccountId.value, 1_234L, currencyCode = "USD")),
            )

            val totals =
                assertIs<P801AccountReconciliationSummaryResult.Success>(
                    adapter.loadAccountReconciliationSummary(ledgerId, assetAccountId),
                ).totals
            assertEquals(
                listOf(
                    P801AccountReconciliationStatusTotal(CurrencyUnit("CNY", 2), P408ReconciliationStatus.CHECKED, -3_000L, 1),
                    P801AccountReconciliationStatusTotal(CurrencyUnit("CNY", 2), P408ReconciliationStatus.PENDING, -6_000L, 2),
                    P801AccountReconciliationStatusTotal(CurrencyUnit("USD", 2), P408ReconciliationStatus.PENDING, 1_234L, 1),
                ),
                totals,
            )
            // The two-leg CNY PENDING group is an exact integer sum of its legs, not a rounded value.
            assertEquals(-6_000L, totals[1].totalMinorUnits)
            assertEquals(2, totals[1].rowCount)
        }
    }

    @Test
    fun anAbsentGroupIsEmptyWhileARealZeroAmountIsARealGroup() {
        withDatabase { _, driver, adapter ->
            // The account holds only a non-real (fee) leg, so both reads answer with nothing at all.
            seedTransaction(driver, "tx-fee", "2026-03-01T02:00:00Z", legs = listOf(SeededLeg("posting-fee", expenseAccountId.value, 100L)))
            val feePage =
                assertIs<P801AccountReconciliationPageResult.Success>(
                    adapter.loadAccountReconciliationPage(ledgerId, expenseAccountId, after = null),
                ).page
            assertTrue(feePage.rows.isEmpty())
            assertNull(feePage.nextCursor)
            val emptyTotals =
                assertIs<P801AccountReconciliationSummaryResult.Success>(
                    adapter.loadAccountReconciliationSummary(ledgerId, expenseAccountId),
                ).totals
            // No synthesized (CNY, PENDING, 0, 0) row: an absent group is absent (D-089).
            assertTrue(emptyTotals.isEmpty())

            // A real zero-amount leg is the OTHER kind of answer: a real group with one real row,
            // which is exactly what makes "no data" and "a zero sum" distinguishable.
            seedTransaction(driver, "tx-zero", "2026-03-02T02:00:00Z", legs = listOf(SeededLeg("posting-zero", assetAccountId.value, 0L)))
            assertEquals(
                listOf(P801AccountReconciliationStatusTotal(CurrencyUnit("CNY", 2), P408ReconciliationStatus.PENDING, 0L, 1)),
                assertIs<P801AccountReconciliationSummaryResult.Success>(
                    adapter.loadAccountReconciliationSummary(ledgerId, assetAccountId),
                ).totals,
            )
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun withDatabase(block: (LedgerDatabase, JdbcSqliteDriver, SqlDelightP801AccountReconciliationReadAdapter) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, migrationProperties())
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            SqlDelightCatalogStore(database, driver).bootstrap(ledgerId, defaultCatalogSeed())
            block(database, driver, SqlDelightP801AccountReconciliationReadAdapter(database))
        } finally {
            driver.close()
        }
    }

    /**
     * One synthetic current-version transaction with its own posting set and the given legs. The
     * numeric statistics projection is left NULL on purpose: the queue and the summary are defined
     * on `occurred_at` and must never depend on it.
     */
    private fun seedTransaction(
        driver: JdbcSqliteDriver,
        transactionId: String,
        occurredAt: String,
        note: String? = null,
        kind: String = "ACCOUNT_TRANSFER",
        legs: List<SeededLeg>,
    ) {
        val versionId = "$transactionId-version-1"
        val postingSetId = "$versionId-posting-set"
        driver.execute(
            null,
            "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('$transactionId','${ledgerId.value}','$kind')",
            0,
        )
        driver.execute(null, "INSERT INTO posting_set(posting_set_id, ledger_id) VALUES ('$postingSetId','${ledgerId.value}')", 0)
        driver.execute(
            null,
            "INSERT INTO transaction_version(version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note) " +
                "VALUES ('$versionId','$transactionId','${ledgerId.value}',1,'$postingSetId','$occurredAt','$occurredAt','$occurredAt',${note?.let { "'$it'" } ?: "NULL"})",
            0,
        )
        legs.forEachIndexed { index, leg ->
            driver.execute(
                null,
                "INSERT INTO posting(posting_id, posting_set_id, ledger_id, posting_index, account_id, amount_minor, currency_code, currency_precision) " +
                    "VALUES ('${leg.postingId}','$postingSetId','${ledgerId.value}',$index,'${leg.accountId}',${leg.amountMinor},'${leg.currencyCode}',2)",
                0,
            )
        }
        driver.execute(
            null,
            "INSERT INTO ledger_transaction_current_version(transaction_id, ledger_id, current_version_id) VALUES ('$transactionId','${ledgerId.value}','$versionId')",
            0,
        )
    }

    /** An extra `catalog_account` row (the seed provides only the default asset and expense accounts). */
    private fun seedCatalogAccount(
        driver: JdbcSqliteDriver,
        accountId: String,
        kind: String,
        ownedByUser: Boolean,
        realAccount: Boolean,
    ) {
        driver.execute(
            null,
            "INSERT INTO catalog_account(ledger_id, account_id, name, kind, currency_code, currency_precision, owned_by_user, real_account, system_role, hidden, active) " +
                "VALUES ('${ledgerId.value}','$accountId','$accountId','$kind','CNY',2,${if (ownedByUser) 1 else 0},${if (realAccount) 1 else 0},NULL,0,1)",
            0,
        )
    }

    private fun seedReconciliation(
        driver: JdbcSqliteDriver,
        postingId: String,
        status: String,
    ) {
        driver.execute(
            null,
            "INSERT INTO posting_reconciliation(ledger_id, reconciliation_id, posting_id, status, latest_sequence) " +
                "VALUES ('${ledgerId.value}','reconciliation-$postingId','$postingId','$status',1)",
            0,
        )
    }

    /**
     * One evidence link to a posting plus its history. The schema's sequence/transition guards
     * require sequence 1 to be `active`, so an invalidated link carries the legal two-row history
     * (1 active -> 2 invalidated) and its LATEST row is what the queue must read.
     */
    private fun seedEvidenceLink(
        driver: JdbcSqliteDriver,
        transactionId: String,
        postingId: String,
        active: Boolean,
    ) {
        val suffix = "$transactionId-$postingId"
        driver.execute(
            null,
            "INSERT INTO import_request(ledger_id, request_id, operation) VALUES ('${ledgerId.value}','import-request-$suffix','intake')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO import_source_record(ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness) " +
                "VALUES ('${ledgerId.value}','source-$suffix','import-request-$suffix','ref-$suffix',0,'ordinary_flow_source','hash-$suffix',1,'valid_incomplete')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO import_evidence(ledger_id, evidence_id, source_id, evidence_kind, observed_at) " +
                "VALUES ('${ledgerId.value}','evidence-$suffix','source-$suffix','source_observation','2026-03-01T02:00:00Z')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO reconciliation_request(ledger_id, request_id, operation, input_fingerprint, outcome) " +
                "VALUES ('${ledgerId.value}','reconciliation-request-$suffix','confirm_link','fingerprint-$suffix','ACCEPTED')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO evidence_link(ledger_id, link_id, evidence_id, posting_id, transaction_id, responsibility, basis_version, match_basis, candidate_id, request_id, created_at) " +
                "VALUES ('${ledgerId.value}','link-$suffix','evidence-$suffix','$postingId','$transactionId','real_account_posting',2," +
                "'amount,currency,direction,account,occurred_at_window','candidate-$suffix','reconciliation-request-$suffix','2026-03-02T02:00:00Z')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO evidence_link_history(ledger_id, link_id, sequence, state, reason, request_id, occurred_at) " +
                "VALUES ('${ledgerId.value}','link-$suffix',1,'active','confirmed','reconciliation-request-$suffix','2026-03-02T02:00:00Z')",
            0,
        )
        if (!active) {
            driver.execute(
                null,
                "INSERT INTO evidence_link_history(ledger_id, link_id, sequence, state, reason, request_id, occurred_at) " +
                    "VALUES ('${ledgerId.value}','link-$suffix',2,'invalidated','corrected','reconciliation-request-$suffix','2026-03-03T02:00:00Z')",
                0,
            )
        }
    }

    private fun migrationProperties(): Properties =
        Properties().apply {
            setProperty("foreign_keys", "true")
        }
}

/** One synthetic posting leg of a seeded transaction: its id, account, signed minor units and currency. */
private data class SeededLeg(
    val postingId: String,
    val accountId: String,
    val amountMinor: Long,
    val currencyCode: String = "CNY",
)
