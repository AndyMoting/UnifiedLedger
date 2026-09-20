package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.CatalogAdmissionReader
import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.CorrectTransactionVersionIdSource
import com.unifiedledger.application.CorrectTransactionVersionIds
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.TransactionVoidFactIdSource
import com.unifiedledger.application.TransactionVoidFactIds
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.time.Instant

/**
 * Shared anonymous P7-05 fixture and database harness (every id, amount and instant is
 * synthetic). One file-backed database per test so the commit ports' transactions behave
 * exactly as they do on a device file; no catalog rows are needed because the P7-05 commit
 * ports receive the authoritative catalog through the injected admission reader, and the
 * catalog object is the same one the read-side use cases consume.
 */
internal object P705Fixture {
    val ledgerId = LedgerId("ledger-p705")
    val otherLedgerId = LedgerId("ledger-p705-other")
    val cny = CurrencyUnit("CNY", 2)
    val bankA = AccountId("asset-bank-a")
    val bankB = AccountId("asset-bank-b")
    val expenseAccount = AccountId("expense-food-account")
    val incomeAccount = AccountId("income-salary-account")
    val foodParent = CategoryId("category-food-parent")
    val food = CategoryId("category-food")
    val salaryParent = CategoryId("category-salary-parent")
    val salary = CategoryId("category-salary")

    /** March 5 2026 10:00 Asia/Shanghai, the frozen V-01..V-20 statistics instant. */
    val marchStatistics: Instant = Instant.parse("2026-03-05T02:00:00Z")

    /** April 5 2026 10:00 Asia/Shanghai, the frozen V-07 cross-month target instant. */
    val aprilStatistics: Instant = Instant.parse("2026-04-05T02:00:00Z")

    val voidedAt: Instant = Instant.parse("2026-05-01T00:00:00Z")

    fun catalog(): LedgerCatalog =
        when (
            val result =
                LedgerCatalog.create(
                    accounts =
                        listOf(
                            Account(bankA, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true, name = "银行A"),
                            Account(bankB, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true, name = "银行B"),
                            Account(expenseAccount, ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false),
                            Account(incomeAccount, ledgerId, AccountKind.INCOME, cny, ownedByUser = false, realAccount = false),
                        ),
                    categories =
                        listOf(
                            Category(foodParent, ledgerId, null, null, active = true, name = "餐饮"),
                            Category(food, ledgerId, foodParent, expenseAccount, active = true, name = "餐饮-午餐"),
                            Category(salaryParent, ledgerId, null, null, active = true, kind = CategoryKind.INCOME, name = "收入"),
                            Category(salary, ledgerId, salaryParent, incomeAccount, active = true, kind = CategoryKind.INCOME, name = "收入-工资"),
                        ),
                )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> error("fixture catalog must be valid")
        }

    val admissionReader: CatalogAdmissionReader = CatalogAdmissionReader { catalog() }
}

/** Monotonic request-id and id sources so every vector uses fresh identities deterministically. */
internal class P705Ids(
    private val prefix: String,
) {
    private var counter = 0
    private var lastRequest: String? = null
    private var lastVersion: String? = null

    private fun next(): String {
        counter += 1
        return "$prefix-$counter"
    }

    fun requestId(): RequestId = RequestId(next()).also { lastRequest = it.value }

    fun correctIds(): CorrectTransactionVersionIds =
        CorrectTransactionVersionIds(
            confirmationId = ConfirmationId(next()),
            versionId = TransactionVersionId(next()),
            postingSetId = PostingSetId(next()),
            categoryPostingId = PostingId(next()),
            fundingPostingId = PostingId(next()),
        ).also { lastVersion = it.versionId.value }

    fun voidIds(): TransactionVoidFactIds =
        TransactionVoidFactIds(
            confirmationId = ConfirmationId(next()),
            factId = next(),
        )

    /** The request id minted last (the vector's committed identity). */
    val lastRequestId: String get() = checkNotNull(lastRequest)

    /** The version id minted last (the vector's committed version). */
    val lastVersionId: String get() = checkNotNull(lastVersion)

    val correctSource: CorrectTransactionVersionIdSource = CorrectTransactionVersionIdSource { correctIds() }
    val voidSource: TransactionVoidFactIdSource = TransactionVoidFactIdSource { voidIds() }
}

/** Fixed-clock port; the P7-05 fact timestamp is the audit time the slice freezes. */
internal fun fixedClock(instant: Instant): LedgerClock = LedgerClock { instant }

internal class P705Database private constructor(
    private val path: Path,
    private val ownsFile: Boolean,
) : AutoCloseable {
    private val driver = JdbcSqliteDriver("jdbc:sqlite:${path.absolutePathString()}")
    val database: LedgerDatabase
    val readAdapter: SqlDelightLedgerCurrentStateReadAdapter
    val correctionPort: SqlDelightTransactionCorrectionCommitPort
    val voidPort: SqlDelightTransactionVoidCommitPort

    init {
        if (ownsFile) LedgerDatabase.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        database = LedgerDatabase(driver)
        readAdapter = SqlDelightLedgerCurrentStateReadAdapter(database)
        correctionPort = SqlDelightTransactionCorrectionCommitPort(database, driver)
        voidPort = SqlDelightTransactionVoidCommitPort(database, driver)
    }

    override fun close() {
        driver.close()
        if (ownsFile) Files.deleteIfExists(path)
    }

    fun ledgerQueryCount(sql: String): Long =
        driver
            .executeQuery(
                null,
                sql,
                { cursor ->
                    check(cursor.next().value)
                    app.cash.sqldelight.db.QueryResult
                        .Value(requireNotNull(cursor.getLong(0)))
                },
                0,
            ).value

    fun ledgerQueryText(sql: String): String =
        driver
            .executeQuery(
                null,
                sql,
                { cursor ->
                    check(cursor.next().value)
                    app.cash.sqldelight.db.QueryResult
                        .Value(requireNotNull(cursor.getString(0)))
                },
                0,
            ).value

    fun ledgerQueryLongs(sql: String): List<Long> =
        driver
            .executeQuery(
                null,
                sql,
                { cursor ->
                    val values = buildList { while (cursor.next().value) add(requireNotNull(cursor.getLong(0))) }
                    app.cash.sqldelight.db.QueryResult
                        .Value(values)
                },
                0,
            ).value

    fun ledgerQueryTexts(sql: String): List<String> =
        driver
            .executeQuery(
                null,
                sql,
                { cursor ->
                    val values = buildList { while (cursor.next().value) add(requireNotNull(cursor.getString(0))) }
                    app.cash.sqldelight.db.QueryResult
                        .Value(values)
                },
                0,
            ).value

    /** A transaction of an arbitrary kind with a minimal chain, for the support-matrix vectors. */
    fun insertTransactionOfKind(
        transactionId: String,
        kind: TransactionKind,
    ) {
        database.ledgerQueries.insertTransaction(transactionId, P705Fixture.ledgerId.value, kind.name)
        database.ledgerQueries.insertPostingSet("$transactionId-posting-set-1", P705Fixture.ledgerId.value)
        database.ledgerQueries.insertTransactionVersion(
            "$transactionId-version-1",
            transactionId,
            P705Fixture.ledgerId.value,
            1L,
            "$transactionId-posting-set-1",
            P705Fixture.marchStatistics.toString(),
            P705Fixture.marchStatistics.toString(),
            P705Fixture.marchStatistics.toString(),
            null,
        )
        database.ledgerQueries.insertTransactionCurrentVersion(
            transactionId,
            P705Fixture.ledgerId.value,
            "$transactionId-version-1",
        )
    }

    /** Every transaction id of the fixture ledger (the V-23 domain-equivalence denominator). */
    fun transactionIds(): List<String> =
        ledgerQueryTexts(
            "SELECT transaction_id FROM ledger_transaction WHERE ledger_id = '${P705Fixture.ledgerId.value}' ORDER BY transaction_id",
        )

    /** The void/restore fact sequence of one transaction, as the domain type would order it. */
    fun voidStateOf(transactionId: String): com.unifiedledger.domain.TransactionVoidState =
        com.unifiedledger.domain.TransactionVoidState.of(
            database.ledgerQueries
                .transactionVoidFactsForTransaction(P705Fixture.ledgerId.value, transactionId) { sequence, factKind, _, _, _, _, _ ->
                    com.unifiedledger.domain.TransactionVoidFact(
                        sequence = sequence.toInt(),
                        factKind =
                            com.unifiedledger.domain.TransactionVoidFactKind
                                .fromStorage(factKind)
                                ?: com.unifiedledger.domain.TransactionVoidFactKind.VOID,
                    )
                }.executeAsList(),
        )

    /** Direct driver access for the raw guard vectors (V-15). */
    fun driverExecute(sql: String) {
        driver.execute(null, sql, 0)
    }

    /** A raw fact insert bypassing the commit port, so the database guards are exercised. */
    fun insertRawVoidFact(
        transactionId: String,
        sequence: Long,
        factKind: String,
        reuseExistingRequest: Boolean = false,
        factId: String = "raw-fact-$sequence",
    ) {
        val existingRequestId =
            ledgerQueryText("SELECT request_id FROM transaction_void_fact WHERE transaction_id = '$transactionId' LIMIT 1")
        val requestId = if (reuseExistingRequest) existingRequestId else "raw-request-$sequence"
        val reasonCode = "other"
        driver.execute(
            null,
            "INSERT INTO transaction_void_request(ledger_id, request_id, transaction_id, fact_kind, reason_code, reason_note, confirmation_marker) " +
                "VALUES ('${P705Fixture.ledgerId.value}', '$requestId', '$transactionId', '$factKind', '$reasonCode', NULL, 'explicit_manual_save')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO transaction_void_fact(ledger_id, transaction_id, sequence, fact_id, fact_kind, reason_code, reason_note, request_id, confirmation_id, created_at) " +
                "VALUES ('${P705Fixture.ledgerId.value}', '$transactionId', $sequence, '$factId', '$factKind', '$reasonCode', NULL, '$requestId', 'raw-confirmation', '2026-05-01T00:00:00Z')",
            0,
        )
    }

    /**
     * A frozen-silo refund relationship row pointing at the transaction (V-14). The relation
     * parent row is inserted first so the foreign key holds; nothing else in the silo changes.
     */
    fun insertLinkedRefund(
        originalTransactionId: String,
        refundTransactionId: String,
    ) {
        val suffix = originalTransactionId.removePrefix("tx-")
        driver.execute(
            null,
            "INSERT INTO rg07_relation(ledger_id, relation_id, relation_type, payload_marker) " +
                "VALUES ('${P705Fixture.ledgerId.value}', 'relation-p705-$suffix', 'refund', '{}')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO rg07_refund_relationship(ledger_id, entity_id, relation_id, original_transaction_id, refund_transaction_id, category_id, requested_amount_minor, received_amount_minor, currency_code, currency_precision) " +
                "VALUES ('${P705Fixture.ledgerId.value}', 'entity-p705-$suffix', 'relation-p705-$suffix', '$originalTransactionId', '$refundTransactionId', '${P705Fixture.food.value}', 3000, 3000, 'CNY', 2)",
            0,
        )
    }

    /**
     * Minimal import spine so an import-created transaction is recognisable: request, source,
     * evidence, candidate, the pending -> confirmed status pair and the creation confirmation.
     */
    fun insertImportCreationConfirmation(transactionId: String) {
        val suffix = transactionId.removePrefix("tx-")
        val requestId = "request-p705-$suffix"
        val sourceId = "source-p705-$suffix"
        database.ledgerQueries.claimImportRequest(P705Fixture.ledgerId.value, requestId, "confirm_candidate")
        database.ledgerQueries.insertImportSourceRecord(
            P705Fixture.ledgerId.value,
            sourceId,
            requestId,
            "input-ref-p705",
            0L,
            "ordinary_flow_source",
            "content-hash-p705",
            1L,
            "valid_incomplete",
            null,
            null,
            null,
            null,
            null,
            null,
            "UNRESOLVED",
            "rule-p705",
            1L,
            P705Fixture.marchStatistics.toString(),
        )
        database.ledgerQueries.insertImportEvidence(
            P705Fixture.ledgerId.value,
            "evidence-p705-$suffix",
            sourceId,
            "source_observation",
            P705Fixture.marchStatistics.toString(),
        )
        database.ledgerQueries.insertImportCandidate(
            P705Fixture.ledgerId.value,
            "candidate-p705-$suffix",
            sourceId,
            "ordinary_flow",
            "exact",
            "rule-p705",
            1L,
        )
        database.ledgerQueries.insertImportStatusHistory(
            P705Fixture.ledgerId.value,
            "candidate-p705-$suffix",
            1L,
            "status-p705-$suffix-pending",
            "pending_confirmation",
            requestId,
            "creation",
        )
        database.ledgerQueries.insertImportStatusHistory(
            P705Fixture.ledgerId.value,
            "candidate-p705-$suffix",
            2L,
            "status-p705-$suffix",
            "confirmed",
            requestId,
            "creation",
        )
        database.ledgerQueries.insertImportConfirmation(
            P705Fixture.ledgerId.value,
            "confirmation-p705-$suffix",
            requestId,
            "candidate-p705-$suffix",
            "status-p705-$suffix",
            transactionId,
            "creation",
            P705Fixture.marchStatistics.toString(),
        )
    }

    /** One manual `EXPENSE` chain: transaction, version 1, posting set, two postings, pointer. */
    fun insertOrdinaryExpense(
        transactionId: String,
        amountMinor: Long,
        statisticsAt: Instant = P705Fixture.marchStatistics,
        occurredAt: Instant = statisticsAt,
        note: String? = "lunch",
        versionId: String = "$transactionId-version-1",
        postingSetId: String = "$transactionId-posting-set-1",
    ) {
        insertOrdinary(
            transactionId = transactionId,
            kind = TransactionKind.EXPENSE,
            amountMinor = amountMinor,
            statisticsAt = statisticsAt,
            occurredAt = occurredAt,
            note = note,
            versionId = versionId,
            postingSetId = postingSetId,
            categoryAccount = P705Fixture.expenseAccount,
            fundingAccount = P705Fixture.bankA,
        )
    }

    /** One manual `INCOME` chain in the creation factories' leg order (funding leg first). */
    fun insertOrdinaryIncome(
        transactionId: String,
        amountMinor: Long,
        statisticsAt: Instant = P705Fixture.marchStatistics,
        occurredAt: Instant = statisticsAt,
        note: String? = "salary",
        versionId: String = "$transactionId-version-1",
        postingSetId: String = "$transactionId-posting-set-1",
    ) {
        insertOrdinary(
            transactionId = transactionId,
            kind = TransactionKind.INCOME,
            amountMinor = amountMinor,
            statisticsAt = statisticsAt,
            occurredAt = occurredAt,
            note = note,
            versionId = versionId,
            postingSetId = postingSetId,
            categoryAccount = P705Fixture.incomeAccount,
            fundingAccount = P705Fixture.bankA,
        )
    }

    private fun insertOrdinary(
        transactionId: String,
        kind: TransactionKind,
        amountMinor: Long,
        statisticsAt: Instant,
        occurredAt: Instant,
        note: String?,
        versionId: String,
        postingSetId: String,
        categoryAccount: AccountId,
        fundingAccount: AccountId,
    ) {
        database.ledgerQueries.insertTransaction(transactionId, P705Fixture.ledgerId.value, kind.name)
        database.ledgerQueries.insertPostingSet(postingSetId, P705Fixture.ledgerId.value)
        database.ledgerQueries.insertTransactionVersion(
            versionId,
            transactionId,
            P705Fixture.ledgerId.value,
            1L,
            postingSetId,
            occurredAt.toString(),
            statisticsAt.toString(),
            occurredAt.toString(),
            note,
        )
        val postings =
            if (kind == TransactionKind.EXPENSE) {
                listOf(categoryAccount to amountMinor, fundingAccount to -amountMinor)
            } else {
                listOf(fundingAccount to amountMinor, categoryAccount to -amountMinor)
            }
        postings.forEachIndexed { index, (accountId, amount) ->
            database.ledgerQueries.insertPosting(
                "$transactionId-posting-$index",
                postingSetId,
                P705Fixture.ledgerId.value,
                index.toLong(),
                accountId.value,
                amount,
                P705Fixture.cny.code,
                P705Fixture.cny.precision.toLong(),
            )
        }
        database.ledgerQueries.insertTransactionCurrentVersion(
            transactionId,
            P705Fixture.ledgerId.value,
            versionId,
        )
    }

    /** Manual creation lineage of one expense chain, so the creation entry reads 手工创建. */
    fun insertManualExpenseCreationReceipt(
        transactionId: String,
        requestId: String = "$transactionId-creation-request",
    ) {
        database.ledgerQueries.claimManualExpenseRequest(
            ledger_id = P705Fixture.ledgerId.value,
            request_id = requestId,
            amount_minor = 10_000L,
            currency_code = P705Fixture.cny.code,
            currency_precision = P705Fixture.cny.precision.toLong(),
            category_id = P705Fixture.food.value,
            payment_account_id = P705Fixture.bankA.value,
            occurred_at = P705Fixture.marchStatistics.toString(),
            note = "",
            confirmation_marker = "explicit_manual_save",
        )
        database.ledgerQueries.insertConfirmedExpenseReceipt(
            P705Fixture.ledgerId.value,
            requestId,
            "$transactionId-creation-confirmation",
            transactionId,
        )
    }

    /** The current-version postings of one transaction, as the read model would project them. */
    fun currentPostings(transactionId: String): List<Pair<String, Long>> =
        database.ledgerQueries
            .currentVersionPostingsForTransaction(P705Fixture.ledgerId.value, transactionId) { _, _, accountId, amountMinor, _, _ ->
                accountId to amountMinor
            }.executeAsList()

    fun currentVersionId(transactionId: String): String =
        ledgerQueryText(
            "SELECT current_version_id FROM ledger_transaction_current_version " +
                "WHERE ledger_id = '${P705Fixture.ledgerId.value}' AND transaction_id = '$transactionId'",
        )

    fun versionCount(transactionId: String): Long = ledgerQueryCount("SELECT count(*) FROM transaction_version WHERE transaction_id = '$transactionId'")

    companion object {
        fun create(prefix: String = "p705-"): P705Database = P705Database(Files.createTempFile(prefix, ".db"), ownsFile = true)

        /** A second connection to a database created by [create] (concurrency vectors). */
        fun open(path: Path): P705Database = P705Database(path, ownsFile = false)
    }

    /** The owned file path, so a test can open a second connection for a concurrency vector. */
    val filePath: Path get() = path
}
