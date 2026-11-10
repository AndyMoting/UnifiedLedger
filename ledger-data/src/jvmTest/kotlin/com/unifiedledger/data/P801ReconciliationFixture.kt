package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.BindReconciliationEvidence
import com.unifiedledger.application.CATALOG_MANAGED_CURRENCY
import com.unifiedledger.application.ConfirmReconciliationLink
import com.unifiedledger.application.DEFAULT_HIDDEN_EXPENSE_ACCOUNT_ID
import com.unifiedledger.application.DEFAULT_MANAGEABLE_ACCOUNT_ID
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.P408MaterializationRequest
import com.unifiedledger.application.P408MaterializeResult
import com.unifiedledger.application.P801AccountReconciliationPageResult
import com.unifiedledger.application.P801AccountReconciliationQueueRow
import com.unifiedledger.application.P801ReconciliationIdSource
import com.unifiedledger.application.P801ReconciliationIds
import com.unifiedledger.application.ProposeReconciliationCandidates
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import java.util.Properties
import kotlin.test.assertIs
import kotlin.time.Instant

/** The fixture ledger every P8-01a i2 test writes to (synthetic, anonymous). */
internal const val P801_FIXTURE_LEDGER: String = "ledger-p801-i2"

/** The one injected confirmation instant; every P8-01a i2 test pins the same provenance time. */
internal val P801_FIXED_NOW: Instant = Instant.parse("2026-03-05T00:00:00Z")

/** One synthetic posting leg: its id, account, signed minor units and currency code. */
internal data class P801Leg(
    val postingId: String,
    val accountId: String,
    val amountMinor: Long,
    val currencyCode: String = "CNY",
)

/** The ids a seeded import evidence row owns. */
internal data class P801SeededEvidence(
    val evidenceId: String,
    val sourceId: String,
    val candidateId: String,
)

/** Deterministic reconciliation ids: a readable counter instead of random UUIDs. */
internal class P801SequentialIds : P801ReconciliationIdSource {
    private var counter = 0

    override fun next(): P801ReconciliationIds {
        counter += 1
        return P801ReconciliationIds(
            requestId = "request-p801-$counter",
            linkId = "link-p801-$counter",
            reconciliationId = "reconciliation-p801-$counter",
        )
    }
}

/**
 * Real in-memory SQLDelight database plus the real stores and the three P8-01a i2 use cases over
 * them (spec sections 2.4/2.5/2.7). Nothing is mocked: the read adapter, the evidence projection
 * store and the P408 reconciliation store are the production implementations, so every assertion
 * below is about real SQL and real writes.
 *
 * All rows are synthetic and anonymous, and every instant is fixed.
 */
internal class P801ReconciliationFixture : AutoCloseable {
    val ledgerId: LedgerId = LedgerId(P801_FIXTURE_LEDGER)
    val assetAccountId: AccountId = AccountId(DEFAULT_MANAGEABLE_ACCOUNT_ID)
    val otherAssetAccountId: AccountId = AccountId("asset-second")
    val usdAccountId: AccountId = AccountId("asset-usd")
    val expenseAccountId: AccountId = AccountId(DEFAULT_HIDDEN_EXPENSE_ACCOUNT_ID)
    val cny: CurrencyUnit = CATALOG_MANAGED_CURRENCY
    val usd: CurrencyUnit = CurrencyUnit("USD", 2)
    val ids: P801SequentialIds = P801SequentialIds()
    val clock: LedgerClock = LedgerClock { P801_FIXED_NOW }
    private val driver: JdbcSqliteDriver =
        JdbcSqliteDriver(
            JdbcSqliteDriver.IN_MEMORY,
            Properties().apply { setProperty("foreign_keys", "true") },
        )
    val database: LedgerDatabase
    val catalogStore: SqlDelightCatalogStore
    val projections: SqlDelightEvidenceProjectionStore
    val reconciliation: SqlDelightP408ReconciliationStore
    val read: SqlDelightP801AccountReconciliationReadAdapter
    val propose: ProposeReconciliationCandidates
    val bind: BindReconciliationEvidence
    val confirm: ConfirmReconciliationLink

    init {
        LedgerDatabase.Schema.create(driver)
        database = LedgerDatabase(driver)
        catalogStore = SqlDelightCatalogStore(database, driver)
        check(catalogStore.bootstrap(ledgerId, seed()) is CatalogBootstrapResult.Seeded) { "the fixture catalog must seed" }
        projections = SqlDelightEvidenceProjectionStore(database, driver)
        reconciliation = SqlDelightP408ReconciliationStore(database, driver)
        read = SqlDelightP801AccountReconciliationReadAdapter(database, projections)
        propose = ProposeReconciliationCandidates(read)
        bind = BindReconciliationEvidence(read, projections, catalogStore, ids)
        confirm = ConfirmReconciliationLink(projections, reconciliation, clock, ids)
    }

    override fun close() {
        driver.close()
    }

    /** The product seed plus a second real asset account and a USD one (for the reject vectors). */
    private fun seed(): CatalogSeed {
        val base = defaultCatalogSeed()
        return base.copy(
            accounts =
                base.accounts +
                    CatalogSeedAccount(otherAssetAccountId, "second-asset", AccountKind.ASSET, cny, ownedByUser = true, realAccount = true, hidden = false) +
                    CatalogSeedAccount(usdAccountId, "usd-asset", AccountKind.ASSET, usd, ownedByUser = true, realAccount = true, hidden = false),
        )
    }

    // ------------------------------------------------------------------ accounting

    /**
     * One synthetic current-version transaction with its own posting set. `statistics_at` mirrors
     * `occurred_at`; the numeric projections are not read by any P8-01a query.
     */
    fun seedTransaction(
        transactionId: String,
        occurredAt: String,
        kind: String = "ACCOUNT_TRANSFER",
        legs: List<P801Leg>,
    ) {
        driver.execute(
            null,
            "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('$transactionId','${ledgerId.value}','$kind')",
            0,
        )
        seedVersion(transactionId, versionNumber = 1, occurredAt = occurredAt, note = null, legs = legs)
        pointCurrentVersion(transactionId, versionNumber = 1)
    }

    /**
     * One transaction with TWO versions where `current_version` names version 2: version 1's
     * postings still exist in their own posting set, so only the posting-facts `current` flag can
     * keep them out of matching (`is_current = 0`).
     */
    fun seedSupersededTransaction(
        transactionId: String,
        firstOccurredAt: String,
        firstLegs: List<P801Leg>,
        secondOccurredAt: String,
        secondLegs: List<P801Leg>,
    ) {
        driver.execute(
            null,
            "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('$transactionId','${ledgerId.value}','ACCOUNT_TRANSFER')",
            0,
        )
        seedVersion(transactionId, versionNumber = 1, occurredAt = firstOccurredAt, note = null, legs = firstLegs)
        seedVersion(transactionId, versionNumber = 2, occurredAt = secondOccurredAt, note = null, legs = secondLegs)
        pointCurrentVersion(transactionId, versionNumber = 2)
    }

    private fun seedVersion(
        transactionId: String,
        versionNumber: Int,
        occurredAt: String,
        note: String?,
        legs: List<P801Leg>,
    ) {
        val versionId = "$transactionId-version-$versionNumber"
        val postingSetId = "$versionId-posting-set"
        val storedNote = note?.replace("'", "''")?.let { "'$it'" } ?: "NULL"
        driver.execute(null, "INSERT INTO posting_set(posting_set_id, ledger_id) VALUES ('$postingSetId','${ledgerId.value}')", 0)
        driver.execute(
            null,
            "INSERT INTO transaction_version(version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note) " +
                "VALUES ('$versionId','$transactionId','${ledgerId.value}',$versionNumber,'$postingSetId','$occurredAt','$occurredAt','$occurredAt',$storedNote)",
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
    }

    private fun pointCurrentVersion(
        transactionId: String,
        versionNumber: Int,
    ) {
        driver.execute(
            null,
            "INSERT INTO ledger_transaction_current_version(transaction_id, ledger_id, current_version_id) " +
                "VALUES ('$transactionId','${ledgerId.value}','$transactionId-version-$versionNumber')",
            0,
        )
    }

    // ------------------------------------------------------------------ import evidence

    /** One fully resolved import evidence row with its own request, source and candidate. */
    fun seedEvidence(
        key: String,
        amountMinor: Long,
        direction: String,
        occurredAt: String,
        currencyCode: String = "CNY",
        currencyPrecision: Int = 2,
    ): P801SeededEvidence {
        seedImportRequest(key)
        driver.execute(
            null,
            "INSERT INTO import_source_record(ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, " +
                "contract_version, completeness, amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token) " +
                "VALUES ('${ledgerId.value}','source-$key','import-request-$key','ref-$key',0,'ordinary_flow_source','hash-$key',1,'valid_complete'," +
                "$amountMinor,'$currencyCode',$currencyPrecision,'$occurredAt','$direction','settled')",
            0,
        )
        return seedEvidenceRows(key, observedAt = occurredAt)
    }

    /** One VALID_INCOMPLETE evidence: every raw economic fact is absent (D-111 unresolved). */
    fun seedIncompleteEvidence(key: String): P801SeededEvidence {
        seedImportRequest(key)
        driver.execute(
            null,
            "INSERT INTO import_source_record(ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, " +
                "contract_version, completeness) " +
                "VALUES ('${ledgerId.value}','source-$key','import-request-$key','ref-$key',0,'ordinary_flow_source','hash-$key',1,'valid_incomplete')",
            0,
        )
        return seedEvidenceRows(key, observedAt = null)
    }

    private fun seedImportRequest(key: String) {
        driver.execute(
            null,
            "INSERT INTO import_request(ledger_id, request_id, operation) VALUES ('${ledgerId.value}','import-request-$key','intake')",
            0,
        )
    }

    private fun seedEvidenceRows(
        key: String,
        observedAt: String?,
    ): P801SeededEvidence {
        val observed = observedAt?.let { "'$it'" } ?: "NULL"
        driver.execute(
            null,
            "INSERT INTO import_evidence(ledger_id, evidence_id, source_id, evidence_kind, observed_at) " +
                "VALUES ('${ledgerId.value}','evidence-$key','source-$key','source_observation',$observed)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO import_candidate(ledger_id, candidate_id, source_id, candidate_kind, confidence, rule, rule_version) " +
                "VALUES ('${ledgerId.value}','candidate-$key','source-$key','ordinary_flow','exact','p408_evidence_projection_v1',1)",
            0,
        )
        return P801SeededEvidence(evidenceId = "evidence-$key", sourceId = "source-$key", candidateId = "candidate-$key")
    }

    /** The port's own materialize call, exactly as the bind use case would issue it. */
    fun materialize(
        evidenceId: String,
        targetAccountId: AccountId = assetAccountId,
        currency: CurrencyUnit = cny,
        requestKey: String = evidenceId,
    ): P408MaterializeResult =
        projections.materialize(
            P408MaterializationRequest(
                ledgerId = ledgerId.value,
                requestId = "materialize-$requestKey",
                evidenceId = evidenceId,
                targetAccountId = targetAccountId.value,
                targetCurrencyCode = currency.code,
                targetCurrencyPrecision = currency.precision,
                materializedAt = P801_FIXED_NOW.toString(),
            ),
        )

    /** One ACTIVE evidence link to a posting, with the legal two-row-free history the schema allows. */
    fun seedActiveLink(
        key: String,
        evidenceId: String,
        postingId: String,
        transactionId: String,
    ) {
        driver.execute(
            null,
            "INSERT INTO reconciliation_request(ledger_id, request_id, operation, input_fingerprint, outcome) " +
                "VALUES ('${ledgerId.value}','reconciliation-request-$key','confirm_link','fingerprint-$key','ACCEPTED')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO evidence_link(ledger_id, link_id, evidence_id, posting_id, transaction_id, responsibility, basis_version, match_basis, candidate_id, request_id, created_at) " +
                "VALUES ('${ledgerId.value}','link-$key','$evidenceId','$postingId','$transactionId','real_account_posting',2," +
                "'amount,currency,direction,account,occurred_at_window','candidate-$key','reconciliation-request-$key','${P801_FIXED_NOW}')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO evidence_link_history(ledger_id, link_id, sequence, state, reason, request_id, occurred_at) " +
                "VALUES ('${ledgerId.value}','link-$key',1,'active','confirmed','reconciliation-request-$key','${P801_FIXED_NOW}')",
            0,
        )
    }

    // ------------------------------------------------------------------ reads and assertions

    /** The account queue page rows, straight from the i1 read face. */
    fun queueRows(accountId: AccountId): List<P801AccountReconciliationQueueRow> =
        assertIs<P801AccountReconciliationPageResult.Success>(
            read.loadAccountReconciliationPage(ledgerId, accountId, after = null),
        ).page.rows

    fun queueRow(
        accountId: AccountId,
        postingId: String,
    ): P801AccountReconciliationQueueRow = queueRows(accountId).first { it.postingId.value == postingId }

    fun scalarLong(sql: String): Long =
        driver
            .executeQuery(
                null,
                sql,
                { cursor ->
                    cursor.next()
                    app.cash.sqldelight.db.QueryResult
                        .Value(cursor.getLong(0) ?: 0L)
                },
                0,
            ).value

    fun scalarText(sql: String): String =
        driver
            .executeQuery(
                null,
                sql,
                { cursor ->
                    cursor.next()
                    app.cash.sqldelight.db.QueryResult
                        .Value(cursor.getString(0) ?: "")
                },
                0,
            ).value

    /**
     * The exact posting set (id, account, amount, currency, precision) in a stable order. Every
     * "reconciliation changed no accounting" assertion compares this string before and after, so an
     * added, removed or amended posting cannot slip through a count-only check.
     */
    fun postingFingerprint(): String =
        scalarText(
            "SELECT coalesce(group_concat(row, '|'), '') FROM (" +
                "SELECT posting_id || ':' || account_id || ':' || amount_minor || ':' || currency_code || ':' || currency_precision AS row " +
                "FROM posting WHERE ledger_id = '${ledgerId.value}' ORDER BY posting_id)",
        )

    /**
     * Content fingerprint of every formal transaction and version row (id, kind, canonical kind,
     * version number, posting set, the three time texts, note, confirmation id). Row COUNTS cannot
     * see an amended value, so the bind/confirm zero-accounting-effect assertions compare this
     * string before and after.
     */
    fun transactionFingerprint(): String =
        scalarText(
            "SELECT coalesce(group_concat(row, '|'), '') FROM (" +
                "SELECT transaction_id || ':' || kind || ':' || coalesce(canonical_kind, '') AS row " +
                "FROM ledger_transaction WHERE ledger_id = '${ledgerId.value}' ORDER BY transaction_id)",
        ) +
            "||" +
            scalarText(
                "SELECT coalesce(group_concat(row, '|'), '') FROM (" +
                    "SELECT version_id || ':' || transaction_id || ':' || version_number || ':' || posting_set_id || ':' || " +
                    "occurred_at || ':' || statistics_at || ':' || effective_at || ':' || coalesce(note, '') || ':' || coalesce(confirmation_id, '') AS row " +
                    "FROM transaction_version WHERE ledger_id = '${ledgerId.value}' ORDER BY version_id)",
            )

    /** Per-month, per-currency leg sums — the monthly report's arithmetic over the posting set. */
    fun monthlyTotals(): String =
        scalarText(
            "SELECT coalesce(group_concat(row, '|'), '') FROM (" +
                "SELECT substr(version.occurred_at, 1, 7) || ':' || p.currency_code || ':' || sum(p.amount_minor) AS row " +
                "FROM posting AS p JOIN transaction_version AS version " +
                "ON version.ledger_id = p.ledger_id AND version.posting_set_id = p.posting_set_id " +
                "WHERE p.ledger_id = '${ledgerId.value}' " +
                "GROUP BY substr(version.occurred_at, 1, 7), p.currency_code " +
                "ORDER BY substr(version.occurred_at, 1, 7), p.currency_code)",
        )

    fun projectionCount(evidenceId: String): Long = database.ledgerQueries.selectP408EvidenceProjectionCountForEvidence(ledgerId.value, evidenceId).executeAsOne()

    /** Formal accounting row counts: transactions, versions, posting sets, postings. */
    fun accountingCounts(): List<Long> = countsOf(listOf("ledger_transaction", "transaction_version", "posting_set", "posting"))

    /** [accountingCounts] plus the reconciliation rows the confirm path is allowed to write. */
    fun formalCounts(): List<Long> = accountingCounts() + countsOf(listOf("posting_reconciliation"))

    private fun countsOf(tables: List<String>): List<Long> = tables.map { table -> scalarLong("SELECT count(*) FROM $table WHERE ledger_id = '${ledgerId.value}'") }
}
