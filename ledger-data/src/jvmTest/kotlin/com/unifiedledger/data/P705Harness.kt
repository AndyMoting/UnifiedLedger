package com.unifiedledger.data

import app.cash.sqldelight.db.SqlCursor
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
    private var driverClosed = false
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
        closeDriver()
        if (ownsFile) Files.deleteIfExists(path)
    }

    /**
     * Test-only: close the connection while KEEPING the file, so a test can reopen the same path on a
     * fresh connection ([P705Database.open]) and assert a real close/reopen rather than a second
     * concurrent connection. The owner's [close] still deletes the file afterwards.
     *
     * The close must be genuine. For a file-backed url, SQLDelight 2.3.2's `JdbcSqliteDriver`
     * delegates `close()` to `ThreadedConnectionManager.close()`, which is an empty method, and its
     * `closeConnection` drops the thread-local handle so the next query silently reopens a fresh
     * connection. This method therefore closes the underlying JDBC connection directly, leaving the
     * driver's thread-local pointing at the now-closed connection: any further read through this
     * harness fails rather than transparently reconnecting, which is what lets the V-16 reopen test
     * prove it is not reading through the original connection.
     */
    fun closePreservingFile() {
        if (driverClosed) return
        driverClosed = true
        driver.getConnection().close()
    }

    private fun closeDriver() {
        if (driverClosed) return
        driverClosed = true
        driver.close()
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

    /** First column of the first row, or `null` when the query returns no row. */
    fun ledgerQueryTextOrNull(sql: String): String? =
        driver
            .executeQuery(
                null,
                sql,
                { cursor ->
                    app.cash.sqldelight.db.QueryResult
                        .Value(if (cursor.next().value) cursor.getString(0) else null)
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

    /**
     * Environment identity for a timed reading artifact (D-158 section 5), so a printed reading is
     * self-identifying. The SQLite engine version is read from the live connection rather than
     * hardcoded, so it cannot drift from the bundled driver. Only non-personal environment facts are
     * reported (engine, driver, JVM, OS); no host name, user name, or local path is included.
     */
    fun environmentIdentity(): List<String> {
        val sqlDelightDriver = JdbcSqliteDriver::class.java
        val xerialDriver =
            java.sql.DriverManager
                .getDrivers()
                .toList()
                .firstOrNull { it.javaClass.name.startsWith("org.sqlite") }
        return listOf(
            "sqlite-version=${ledgerQueryText("SELECT sqlite_version()")}",
            "jdbc-driver=${driverIdentity(sqlDelightDriver)}",
            "sqlite-jdbc=${xerialDriver?.let { driverIdentity(it.javaClass) } ?: "org.sqlite.JDBC not registered"}",
            "jvm=${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}",
            "os=${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}",
        )
    }

    private fun driverIdentity(clazz: Class<*>): String {
        val version = clazz.getPackage()?.implementationVersion
        if (version != null) return "${clazz.name} $version"
        // Fall back to the artifact file name only (never the path), so a jar whose manifest carries
        // no Implementation-Version still identifies itself without leaking a local absolute path.
        val artifact =
            clazz.protectionDomain
                ?.codeSource
                ?.location
                ?.path
                ?.substringAfterLast('/')
                ?.substringAfterLast('\\')
                ?.takeIf { it.isNotEmpty() }
        return "${clazz.name} ${artifact ?: "version-unknown"}"
    }

    /**
     * A scale fixture of [count] ordinary two-leg chains, written as multi-row INSERTs inside one
     * transaction (D-158 section 5).
     *
     * Why this exists instead of a loop of [insertOrdinaryExpense]/[insertOrdinaryIncome]: those ride
     * the generated queries, and SQLDelight's JDBC driver prepares a fresh statement on every
     * `execute` call. Against this schema (502 triggers, 449 indexes) a fresh prepare of one simple
     * INSERT costs about 5 ms on a file-backed database, while re-executing an already-prepared
     * statement costs about 0.01 ms. Measured standalone with the bundled driver (SQLite 3.51.3):
     * 20,000 single-row INSERTs, each a fresh prepare and auto-commit, take 111 s, so the per-query
     * path's 6 statements per chain (120,000 statements) would take roughly ten minutes. The fixture
     * build is not what D-158 section 5 measures, so it must not dominate the reading. Through this
     * method the same 120,000 rows build in about 2.0 s in-suite (the `build-ms` figure the test
     * prints); that in-suite figure is the honest one to quote, because the trigger and index count
     * above, not the row count, is what makes the writes expensive.
     *
     * The rows are column-for-column what [insertOrdinary] writes for the same chain, including the
     * id suffixes, the leg order (category leg first for an expense, funding leg first for an income)
     * and the ledger-sign convention; only the statement batching differs. The equivalence is pinned
     * by a test rather than asserted here, so the two writers cannot drift silently.
     *
     * [note] receives the 0-based ordinal and returns the note to store, so a caller can vary the
     * note without this method knowing the fixture's naming scheme.
     */
    fun insertOrdinaryBulk(
        count: Int,
        note: (Int) -> String? = { null },
        amountMinor: (Int) -> Long = { 1_000L + it % 97 * 137L },
        statisticsAt: (Int) -> Instant = { P705Fixture.marchStatistics },
    ) {
        require(count >= 0) { "bulk fixture count must not be negative" }
        if (count == 0) return
        database.transaction {
            bulkInsert(
                count = count,
                table = "ledger_transaction",
                columns = "(transaction_id, ledger_id, kind, canonical_kind)",
                row = { index ->
                    val kind = if (index % 2 == 0) TransactionKind.EXPENSE else TransactionKind.INCOME
                    "('${transactionId(index)}', '${P705Fixture.ledgerId.value}', '${kind.name}', NULL)"
                },
            )
            bulkInsert(
                count = count,
                table = "posting_set",
                columns = "(posting_set_id, ledger_id)",
                row = { index -> "('${postingSetId(index)}', '${P705Fixture.ledgerId.value}')" },
            )
            bulkInsert(
                count = count,
                table = "transaction_version",
                columns = "(version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note)",
                row = { index ->
                    val instant = statisticsAt(index).toString()
                    "('${versionId(index)}', '${transactionId(index)}', '${P705Fixture.ledgerId.value}', 1, " +
                        "'${postingSetId(index)}', '$instant', '$instant', '$instant', ${literal(note(index))})"
                },
            )
            bulkInsert(
                count = count * POSTINGS_PER_CHAIN,
                table = "posting",
                columns = "(posting_id, posting_set_id, ledger_id, posting_index, account_id, amount_minor, currency_code, currency_precision)",
                row = { postingOrdinal ->
                    val index = postingOrdinal / POSTINGS_PER_CHAIN
                    val leg = postingOrdinal % POSTINGS_PER_CHAIN
                    val amount = amountMinor(index)
                    val expense = index % 2 == 0
                    // Leg 0 is the category leg for an expense and the funding leg for an income;
                    // the second leg carries the opposite sign, so every chain nets to zero.
                    val (account, signedAmount) =
                        when {
                            expense && leg == 0 -> P705Fixture.expenseAccount to amount
                            expense -> P705Fixture.bankA to -amount
                            leg == 0 -> P705Fixture.bankA to amount
                            else -> P705Fixture.incomeAccount to -amount
                        }
                    "('${postingId(index, leg)}', '${postingSetId(index)}', '${P705Fixture.ledgerId.value}', $leg, " +
                        "'${account.value}', $signedAmount, '${P705Fixture.cny.code}', ${P705Fixture.cny.precision})"
                },
            )
            bulkInsert(
                count = count,
                table = "ledger_transaction_current_version",
                columns = "(transaction_id, ledger_id, current_version_id)",
                row = { index ->
                    "('${transactionId(index)}', '${P705Fixture.ledgerId.value}', '${versionId(index)}')"
                },
            )
        }
    }

    /** The transaction id [insertOrdinaryBulk] mints for one ordinal. */
    fun bulkTransactionId(index: Int): String = transactionId(index)

    private fun bulkInsert(
        count: Int,
        table: String,
        columns: String,
        row: (Int) -> String,
    ) {
        var start = 0
        while (start < count) {
            val end = minOf(start + BULK_CHUNK, count)
            driverExecute(
                "INSERT INTO $table $columns VALUES " + (start until end).joinToString(", ") { row(it) },
            )
            start = end
        }
    }

    private fun transactionId(index: Int): String = "tx-${(index + 1).toString().padStart(5, '0')}"

    private fun postingSetId(index: Int): String = "${transactionId(index)}-posting-set-1"

    private fun versionId(index: Int): String = "${transactionId(index)}-version-1"

    private fun postingId(
        index: Int,
        leg: Int,
    ): String = "${transactionId(index)}-posting-$leg"

    /** A SQL string literal, or `NULL` for an absent optional value. */
    private fun literal(value: String?): String = if (value == null) "NULL" else "'${value.replace("'", "''")}'"

    /**
     * Direct driver query with a caller-driven cursor mapper and one bound parameter (the ledger
     * id at index 0). The D-158 section 5 ablation side needs this surface: the ablation must read
     * the same projection the generated query reads, so it cannot ride the single-column
     * [ledgerQueryTexts]/[ledgerQueryLongs] helpers.
     */
    fun <T> driverQuery(
        sql: String,
        ledgerId: String,
        mapper: (SqlCursor) -> T,
    ): T =
        driver
            .executeQuery(
                null,
                sql,
                { cursor ->
                    app.cash.sqldelight.db.QueryResult
                        .Value(mapper(cursor))
                },
                1,
            ) {
                bindString(0, ledgerId)
            }.value

    /** A raw fact insert bypassing the commit port, so the database guards are exercised. */
    fun insertRawVoidFact(
        transactionId: String,
        sequence: Long,
        factKind: String,
        reuseExistingRequest: Boolean = false,
        factId: String = "raw-fact-$sequence",
    ) {
        val existingRequestId =
            ledgerQueryTextOrNull("SELECT request_id FROM transaction_void_fact WHERE transaction_id = '$transactionId' LIMIT 1")
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
     * The product refund linkage of V-14 (DP-13): the import credit flow's decision snapshot
     * stores `original_transaction_id` (the refunded transaction) and the confirmation created
     * by the same request carries the refund transaction. Both are written through the same
     * named queries the product writer uses, so the vector exercises the product path rather
     * than the writerless `rgXX_` refund silo.
     */
    fun insertProductLinkedRefund(
        originalTransactionId: String,
        refundTransactionId: String,
    ) {
        insertImportCreationConfirmation(refundTransactionId)
        val suffix = refundTransactionId.removePrefix("tx-")
        // The refund decision shape the import spine's CHECK admits: category + credit
        // liability + original transaction, no funding/transfer/mixed fields.
        database.ledgerQueries.insertImportDecisionSnapshot(
            ledger_id = P705Fixture.ledgerId.value,
            request_id = "request-p705-$suffix",
            decision = "confirm",
            candidate_id = "candidate-p705-$suffix",
            expected_content_hash = "content-hash-p705",
            category_id = P705Fixture.food.value,
            funding_account_id = null,
            from_account_id = null,
            to_account_id = null,
            credit_liability_account_id = "liability-p705-$suffix",
            asset_account_id = null,
            original_transaction_id = originalTransactionId,
            asset_leg_minor = null,
            credit_leg_minor = null,
            explicit_confirmed_at = P705Fixture.marchStatistics.toString(),
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

        /** Legs per ordinary chain: the category leg and the funding leg. */
        const val POSTINGS_PER_CHAIN = 2

        /**
         * Rows per multi-row INSERT in [insertOrdinaryBulk]. Well under SQLite's default
         * `SQLITE_MAX_VARIABLE_NUMBER` for the widest row shape here (9 bound-free literals), and
         * large enough that the per-statement prepare cost stops dominating: 500-row chunks build
         * the 20k fixture in well under a second.
         */
        const val BULK_CHUNK = 500
    }

    /** The owned file path, so a test can open a second connection for a concurrency vector. */
    val filePath: Path get() = path
}
